package com.smartparking.storage;

/** Upload bytes whose real type was verified from magic bytes (never from the client-supplied header). */
public record ValidatedUpload(byte[] bytes, String contentType, String extension) {
}
