package com.smartparking.vehicle;

import java.util.Locale;
import java.util.regex.Pattern;

/** Normalisation and validation of Indian vehicle registration numbers. */
public final class PlateNumbers {

    /** State series, e.g. MH12AB1234, DL3CAB1234, KA011234. */
    private static final Pattern STANDARD = Pattern.compile("^[A-Z]{2}[0-9]{1,2}[A-Z]{0,3}[0-9]{4}$");
    /** Bharat series, e.g. 22BH1234AA. */
    private static final Pattern BHARAT = Pattern.compile("^[0-9]{2}BH[0-9]{4}[A-Z]{1,2}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.]");

    private PlateNumbers() {
    }

    /** Upper-cases and strips spaces, hyphens and dots; null becomes the empty string. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return SEPARATORS.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /** True when an already-normalised plate matches one of the accepted Indian formats. */
    public static boolean isValid(String normalized) {
        return normalized != null && (STANDARD.matcher(normalized).matches() || BHARAT.matcher(normalized).matches());
    }
}
