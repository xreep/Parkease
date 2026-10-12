package com.smartparking.common.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.smartparking.support.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ActivityTrackingTest {

    @Autowired MockMvc mvc;
    @Autowired ActivityTracker activity;

    @Test
    void apiRequestsCountAsActivityButHealthChecksDoNot() throws Exception {
        mvc.perform(get("/api/v1/states"));
        Instant before = activity.lastActivity();
        Thread.sleep(5);

        mvc.perform(get("/api/v1/health"));
        assertThat(activity.lastActivity()).isEqualTo(before);

        mvc.perform(get("/api/v1/states"));
        assertThat(activity.lastActivity()).isAfter(before);
    }
}
