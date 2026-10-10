package com.smartparking.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class ForwardedHeadersLogTest {

    @Test
    void nativeModeNamesTheTrustedProxies(CapturedOutput output) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "10\\.\\d+\\.\\d+\\.\\d+");

        new ForwardedHeadersLog(env).logMode();

        assertThat(output.getAll())
                .contains("Forwarded headers: strategy=native")
                .contains("internal-proxies=10\\.\\d+\\.\\d+\\.\\d+")
                .contains("client address = X-Forwarded-For only when the connection comes from an internal proxy");
    }

    @Test
    void nativeModeWithoutAnExplicitListUsesTomcatsDefault(CapturedOutput output) {
        new ForwardedHeadersLog(new MockEnvironment().withProperty("server.forward-headers-strategy", "native"))
                .logMode();

        assertThat(output.getAll()).contains("strategy=native").contains("Tomcat default");
    }

    @Test
    void otherModesSayTheHeaderIsNotUsed(CapturedOutput output) {
        new ForwardedHeadersLog(new MockEnvironment()).logMode();

        assertThat(output.getAll()).contains("Forwarded headers: strategy=none")
                .contains("client address = socket peer");
    }
}
