package src.sync;

import src.database.Database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs sync in the background. The app always reads and writes the local SQLite
 * database, so it works the same offline; every few seconds this pushes local changes
 * to Neon and pulls changes from other computers. When Neon is unreachable it reports
 * "offline" and retries; nothing is lost because changes wait in the outbox.
 */
public final class SyncService {

    public enum State { DISABLED, SYNCING, ONLINE, OFFLINE }

    /** What the UI shows. */
    public record Status(State state, int pending, LocalDateTime lastSync, String message) {
    }

    private static final SyncService INSTANCE = new SyncService();
    private static final long INTERVAL_SECONDS = 15;

    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = new Status(State.DISABLED, 0, null, "Sync not configured");
    private ScheduledExecutorService scheduler;
    private SyncEngine engine;

    private SyncService() {
    }

    public static SyncService get() {
        return INSTANCE;
    }

    /** Starts background sync if a Neon connection is configured. Safe to call twice. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        String url = SyncConfig.jdbcUrl();
        if (url == null) {
            publish(new Status(State.DISABLED, pendingCount(), null,
                    "Working offline only (Neon sync is not configured)"));
            return;
        }
        engine = new SyncEngine(url);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "equiplink-sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Requests a sync soon (e.g. right after the user saves something). */
    public void syncSoon() {
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.schedule(this::runOnce, 1, TimeUnit.SECONDS);
        }
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public Status status() {
        return status;
    }

    public void addListener(Consumer<Status> listener) {
        listeners.add(listener);
        listener.accept(status);
    }

    public void removeListener(Consumer<Status> listener) {
        listeners.remove(listener);
    }

    private synchronized void runOnce() {
        LocalDateTime last = status.lastSync();
        publish(new Status(State.SYNCING, pendingCount(), last, "Syncing with Neon..."));
        try {
            SyncResult result = engine.syncOnce();
            LocalDateTime now = LocalDateTime.now(ZoneId.of(Database.TIME_ZONE));
            String message = "Online · synced"
                    + (result.conflicts() > 0 ? " · " + result.conflicts() + " conflict(s) logged" : "");
            publish(new Status(State.ONLINE, pendingCount(), now, message));
        } catch (Exception exception) {
            Throwable cause = exception;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            publish(new Status(State.OFFLINE, pendingCount(), last,
                    "Offline · changes are saved on this computer"
                            + (cause.getMessage() == null ? "" : " (" + cause.getMessage() + ")")));
        }
    }

    private void publish(Status next) {
        status = next;
        for (Consumer<Status> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException ignored) {
                // A broken listener must not stop sync.
            }
        }
    }

    /** Local changes not yet uploaded. */
    public static int pendingCount() {
        try (Connection connection = Database.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM sync_outbox")) {
            return result.next() ? result.getInt(1) : 0;
        } catch (Exception exception) {
            return 0;
        }
    }
}
