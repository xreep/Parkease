package com.smartparking.admin.locations;

import java.text.Normalizer;
import java.util.Locale;

final class Slugs {

    private Slugs() {
    }

    /** "Kūrla East!" becomes "kurla-east"; empty when the text has no letters or digits. */
    static String of(String name) {
        String folded = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
