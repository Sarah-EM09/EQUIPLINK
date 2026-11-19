package src.sync;

/** Outcome of one sync pass: rows uploaded, rows applied locally, rows skipped. */
public record SyncResult(int pushed, int pulled, int conflicts) {
}
