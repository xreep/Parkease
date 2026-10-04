package com.smartparking.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokensTest {

    @Test
    void newTokenIsUrlSafeAndUnique() {
        String a = Tokens.newToken();
        String b = Tokens.newToken();

        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void sha256IsStableLowercaseHex() {
        assertThat(Tokens.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void emailsAreNormalized() {
        assertThat(Emails.normalize("  Ravi.Kumar@Example.COM ")).isEqualTo("ravi.kumar@example.com");
    }
}
