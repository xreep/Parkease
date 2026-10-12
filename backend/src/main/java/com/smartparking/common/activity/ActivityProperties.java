package com.smartparking.common.activity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How background work follows real use ({@code app.activity.*}).
 *
 * @param activeWindow  how long after the last API request the site still counts as in use (jobs run every minute)
 * @param idleInterval  how often each job still runs while the site is quiet, so the database can sleep in between
 * @param keepAliveUrl  public health URL the server pings to stay awake on hosts that sleep idle instances (blank: off)
 */
@ConfigurationProperties("app.activity")
public record ActivityProperties(Duration activeWindow, Duration idleInterval, String keepAliveUrl) {

    public ActivityProperties {
        if (activeWindow == null) {
            activeWindow = Duration.ofMinutes(15);
        }
        if (idleInterval == null) {
            idleInterval = Duration.ofMinutes(60);
        }
    }
}
