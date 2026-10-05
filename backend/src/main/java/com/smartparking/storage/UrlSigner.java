package com.smartparking.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 signatures for time-limited private file URLs. */
public class UrlSigner {

    private final byte[] keyBytes;

    public UrlSigner(String base64Secret) {
        try {
            this.keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(("file-url:" + base64Secret).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String sign(String key, long expiresEpochSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] sig = mac.doFinal((key + "|" + expiresEpochSeconds).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(String key, long expiresEpochSeconds, String signature, Instant now) {
        if (key == null || signature == null || now.getEpochSecond() > expiresEpochSeconds) {
            return false;
        }
        byte[] expected = sign(key, expiresEpochSeconds).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }
}
