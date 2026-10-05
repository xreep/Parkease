package com.smartparking.storage;

import java.time.Duration;
import java.time.Instant;

public interface FileStorage {

    StoredFile storePublic(ValidatedUpload upload, String folder);

    StoredFile storePrivate(ValidatedUpload upload, String folder);

    /** Never throws: failures are logged. Ignores null keys and keys this storage does not own. */
    void delete(String key);

    SignedUrl privateUrl(String key, Duration ttl);

    /** Public URL for a key returned by {@link #storePublic}; throws IllegalArgumentException for other keys. */
    String publicUrl(String key);

    record SignedUrl(String url, Instant expiresAt) {
    }
}
