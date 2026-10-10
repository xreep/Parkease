package com.smartparking.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * One line at startup saying how client addresses are determined, so a deployment where every visitor shares one
 * rate-limit bucket (proxy not recognised) can be diagnosed from the log.
 */
@Component
public class ForwardedHeadersLog {

    private static final Logger log = LoggerFactory.getLogger(ForwardedHeadersLog.class);

    private final Environment environment;

    public ForwardedHeadersLog(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logMode() {
        String strategy = environment.getProperty("server.forward-headers-strategy", "none");
        if ("native".equals(strategy)) {
            String proxies = environment.getProperty("server.tomcat.remoteip.internal-proxies");
            log.info("Forwarded headers: strategy=native, internal-proxies={}; client address = X-Forwarded-For only "
                            + "when the connection comes from an internal proxy, otherwise the socket peer",
                    StringUtils.hasText(proxies) ? proxies : "(Tomcat default: private, loopback, link-local and CGNAT ranges)");
        } else {
            log.info("Forwarded headers: strategy={}; client address = socket peer (X-Forwarded-For is not used)",
                    strategy);
        }
    }
}
