package com.smartparking.storage;

import com.cloudinary.Cloudinary;
import com.smartparking.common.security.JwtProperties;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StorageConfig implements WebMvcConfigurer {

    private final StorageProperties properties;

    public StorageConfig(StorageProperties properties) {
        this.properties = properties;
    }

    private boolean cloudinaryEnabled() {
        return StringUtils.hasText(properties.cloudinaryUrl());
    }

    @Bean
    UrlSigner urlSigner(JwtProperties jwt) {
        return new UrlSigner(jwt.secret());
    }

    @Bean
    FileStorage fileStorage(UrlSigner signer, Clock clock) {
        if (cloudinaryEnabled()) {
            return new CloudinaryFileStorage(new Cloudinary(properties.cloudinaryUrl()), clock);
        }
        return new LocalFileStorage(Path.of(properties.localDir()), properties.publicBaseUrl(), signer, clock);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (cloudinaryEnabled()) {
            return;
        }
        Path publicDir = Path.of(properties.localDir()).toAbsolutePath().normalize().resolve("public");
        registry.addResourceHandler("/uploads/public/**").addResourceLocations("file:" + publicDir + "/");
    }
}
