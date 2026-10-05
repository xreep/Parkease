package com.smartparking.storage;

import java.time.Instant;

/** A short-lived URL for a private file. */
public record SignedUrlDto(String url, Instant expiresAt) {

    public static SignedUrlDto from(FileStorage.SignedUrl signed) {
        return new SignedUrlDto(signed.url(), signed.expiresAt());
    }
}
