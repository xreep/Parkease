package com.smartparking.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 helpers shared by the payment providers. */
public final class Signatures {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private Signatures() {
    }

    /** Lowercase hex HMAC-SHA256 of {@code data} keyed with {@code secret} (both UTF-8). */
    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /** Constant-time comparison of two hex signatures; false when either is null. */
    public static boolean matches(String expectedHex, String providedHex) {
        if (expectedHex == null || providedHex == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.UTF_8), providedHex.getBytes(StandardCharsets.UTF_8));
    }
}
