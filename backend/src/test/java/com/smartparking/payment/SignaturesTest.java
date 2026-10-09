package com.smartparking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignaturesTest {

    /** Computed once with an independent implementation (Python hmac/hashlib). */
    private static final String KNOWN = "18bfc0baafae8f6367711ee362f2201aaa3654274683100e5367bb9a2bd29cbe";

    @Test
    void matchesKnownVector() {
        assertThat(Signatures.hmacSha256Hex("secret", "order_123|pay_456")).isEqualTo(KNOWN);
    }

    @Test
    void matchesJavaMac() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal("order_123|pay_456".getBytes(StandardCharsets.UTF_8)));

        assertThat(Signatures.hmacSha256Hex("secret", "order_123|pay_456")).isEqualTo(expected);
        assertThat(expected).isEqualTo(KNOWN).isLowerCase();
    }

    @Test
    void matchesComparesHexSafely() {
        assertThat(Signatures.matches(KNOWN, KNOWN)).isTrue();
        assertThat(Signatures.matches(KNOWN, KNOWN.substring(0, KNOWN.length() - 1) + "0")).isFalse();
        assertThat(Signatures.matches(KNOWN, "abc")).isFalse();
        assertThat(Signatures.matches(KNOWN, "")).isFalse();
        assertThat(Signatures.matches(null, KNOWN)).isFalse();
        assertThat(Signatures.matches(KNOWN, null)).isFalse();
        assertThat(Signatures.matches(null, null)).isFalse();
    }
}
