package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UrlSignerTest {

    UrlSigner signer = new UrlSigner("dGVzdC1vbmx5LWp3dC1zZWNyZXQtc21hcnQtcGFya2luZy1wbGF0Zm9ybS0yMDI2");
    Instant now = Instant.parse("2026-10-05T10:00:00Z");
    long expires = now.getEpochSecond() + 300;

    @Test
    void validSignatureVerifies() {
        String sig = signer.sign("local/private/owner-documents/a.pdf", expires);
        assertThat(sig).matches("[A-Za-z0-9_-]+");
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires, sig, now)).isTrue();
    }

    @Test
    void tamperedKeyExpiryOrSignatureFails() {
        String sig = signer.sign("local/private/owner-documents/a.pdf", expires);
        assertThat(signer.verify("local/private/owner-documents/b.pdf", expires, sig, now)).isFalse();
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires + 1, sig, now)).isFalse();
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires, sig + "x", now)).isFalse();
        assertThat(signer.verify("local/private/owner-documents/a.pdf", expires, null, now)).isFalse();
    }

    @Test
    void expiredSignatureFails() {
        String sig = signer.sign("k", expires);
        assertThat(signer.verify("k", expires, sig, now.plusSeconds(301))).isFalse();
    }
}
