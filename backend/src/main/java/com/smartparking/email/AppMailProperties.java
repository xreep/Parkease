package com.smartparking.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mail")
public record AppMailProperties(String host, int port, String username, String password, String from) {
}
