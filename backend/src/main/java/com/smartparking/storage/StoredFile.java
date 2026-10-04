package com.smartparking.storage;

/** Result of storing a file. {@code url} is null for private files. */
public record StoredFile(String key, String url, String contentType, long size) {
}
