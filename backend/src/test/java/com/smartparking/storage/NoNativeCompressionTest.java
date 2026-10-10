package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * brotli4j (pulled in by OpenPDF) ships a native library per OS. When it is on the classpath, the Cloudinary HTTP
 * client switches on Brotli decoding and loads that library, which crashed the Linux container on its first upload.
 * Receipts don't need it, so it stays excluded.
 */
class NoNativeCompressionTest {

    @Test
    void brotli4jIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("com.aayushatharva.brotli4j.Brotli4jLoader"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
