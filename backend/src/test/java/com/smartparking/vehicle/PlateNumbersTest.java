package com.smartparking.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlateNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"MH12AB1234", "DL3CAB1234", "KA011234", "22BH1234AA", "22BH1234A", "MH12A1234", "AP39WX9999"})
    void acceptsIndianPlates(String plate) {
        assertThat(PlateNumbers.isValid(plate)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC123", "", "MH12AB123", "MH12AB12345", "1234MH12AB", "22BH1234AAA", "22BH123AA",
            "M12AB1234", "MH123ABCD1234", "MH12AB12 34"})
    void rejectsOtherShapes(String plate) {
        assertThat(PlateNumbers.isValid(plate)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mh 12 ab 1234", "MH-12-AB-1234", "mh.12.ab.1234", " MH12AB1234 "})
    void normalizesCaseSpacesHyphensAndDots(String raw) {
        assertThat(PlateNumbers.normalize(raw)).isEqualTo("MH12AB1234");
        assertThat(PlateNumbers.isValid(PlateNumbers.normalize(raw))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"22 BH 1234 AA"})
    void normalizesBharatSeries(String raw) {
        assertThat(PlateNumbers.normalize(raw)).isEqualTo("22BH1234AA");
        assertThat(PlateNumbers.isValid("22BH1234AA")).isTrue();
    }

    @Test
    void normalizeNullIsEmpty() {
        assertThat(PlateNumbers.normalize(null)).isEmpty();
        assertThat(PlateNumbers.isValid(null)).isFalse();
    }
}
