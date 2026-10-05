package com.smartparking.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GeoUtilsTest {

    @Test
    void mumbaiToPuneIsAboutOneTwentyKm() {
        assertThat(GeoUtils.distanceKm(19.0760, 72.8777, 18.5204, 73.8567)).isCloseTo(120.0, within(3.0));
    }

    @Test
    void samePointIsZero() {
        assertThat(GeoUtils.distanceKm(18.52, 73.85, 18.52, 73.85)).isCloseTo(0.0, within(1e-9));
    }
}
