package com.smartparking.booking;

import java.security.SecureRandom;

/** Short human-friendly booking references such as {@code PK-7QX2MD} (no 0/O/1/I/L lookalikes). */
public final class BookingCodes {

    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BookingCodes() {
    }

    public static String newCode() {
        StringBuilder code = new StringBuilder("PK-");
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
