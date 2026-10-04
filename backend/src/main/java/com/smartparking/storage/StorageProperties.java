package com.smartparking.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage")
public record StorageProperties(String localDir, String publicBaseUrl, String cloudinaryUrl) {
}
