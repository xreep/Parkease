package com.smartparking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BookingCodesTest {

    @Test
    void codesHaveThePrefixAndSixUnambiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            assertThat(BookingCodes.newCode()).matches("PK-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
        }
    }

    @Test
    void codesVary() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            codes.add(BookingCodes.newCode());
        }
        assertThat(codes.size()).isGreaterThan(45);
    }
}
