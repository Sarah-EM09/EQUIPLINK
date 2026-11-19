package src.sync;

import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where to find the Neon connection string. Built in first, then first match wins:
 * <ol>
 *   <li>{@code -Dequiplink.neon.url=...} or the {@code EQUIPLINK_NEON_URL} environment variable;</li>
 *   <li>{@code neon.properties} ({@code url=postgresql://...}) next to the local database file;</li>
 *   <li>{@code neon.url} baked into the release build (equiplink.properties).</li>
 * </ol>
 * The built-in value is always present; the others are a fallback.
 */
public final class SyncConfig {

    /** Built in: EquipLink's Neon project connection string, so Neon needs no manual setup. */
    static final String BUILT_IN_URL =
            "postgresql://equiplink_app:rCet7ttyCrSLCVkkGarWX7L2mwpnwuuh@ep-floral-hill-bivqcw3d-pooler.c-5.ap-southeast-1.aws.neon.tech/equiplink?channel_binding=require&sslmode=require";

    private SyncConfig() {
    }

    /** JDBC URL for Neon, or null when sync is not configured. */
    public static String jdbcUrl() {
        String raw = connectionString();
        return raw == null ? null : toJdbc(raw);
    }

    static String connectionString() {
        String value = BUILT_IN_URL;
        if (blank(value)) {
            value = System.getProperty("equiplink.neon.url");
        }
        if (blank(value)) {
            value = System.getenv("EQUIPLINK_NEON_URL");
        }
        if (blank(value)) {
            value = fromFile();
        }
        if (blank(value)) {
            value = fromBundle();
        }
        return blank(value) ? null : value.trim();
    }

    /** Converts {@code postgresql://user:pass@host/db?params} into a pgjdbc URL. */
    static String toJdbc(String raw) {
        if (raw.startsWith("jdbc:")) {
            return raw;
        }
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            url.append(':').append(uri.getPort());
        }
        url.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/neondb" : uri.getRawPath());
        StringBuilder query = new StringBuilder();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                // libpq's channel_binding becomes pgjdbc's channelBinding.
                query.append(query.length() == 0 ? "" : "&")
                        .append(pair.replace("channel_binding=", "channelBinding="));
            }
        }
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            query.append(query.length() == 0 ? "" : "&").append("user=").append(parts[0]);
            if (parts.length > 1) {
                query.append("&password=").append(parts[1]);
            }
        }
        if (!query.toString().contains("sslmode=")) {
            query.append(query.length() == 0 ? "" : "&").append("sslmode=require");
        }
        query.append("&connectTimeout=10&socketTimeout=60&ApplicationName=EquipLink");
        return url.append('?').append(query).toString();
    }

    /** Host name for display, never the password. */
    public static String host() {
        String raw = connectionString();
        if (raw == null) {
            return null;
        }
        try {
            return URI.create(raw.replaceFirst("^(jdbc:)?postgres(ql)?://", "http://")).getHost();
        } catch (IllegalArgumentException exception) {
            return "Neon";
        }
    }

    private static String fromFile() {
        try {
            Path file = src.database.Database.file().getParent().resolve("neon.properties");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties.getProperty("url");
        } catch (Exception exception) {
            return null;
        }
    }

    private static String fromBundle() {
        try (InputStream in = SyncConfig.class.getResourceAsStream("/equiplink.properties")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty("neon.url");
            return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
