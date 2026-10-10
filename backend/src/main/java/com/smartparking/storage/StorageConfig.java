package com.smartparking.storage;

import com.cloudinary.Cloudinary;
import com.smartparking.common.security.JwtProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StorageConfig implements WebMvcConfigurer {

    /** cloudinary://<api_key>:<api_secret>@<cloud_name> with no placeholders, spaces or prefix. */
    private static final Pattern CLOUDINARY_URL =
            Pattern.compile("cloudinary://[A-Za-z0-9_-]+:[A-Za-z0-9_-]+@[A-Za-z0-9_-]+");

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
            return new CloudinaryFileStorage(new Cloudinary(checkedCloudinaryUrl(properties.cloudinaryUrl())), clock);
        }
        return new LocalFileStorage(Path.of(properties.localDir()), properties.publicBaseUrl(), signer, clock);
    }

    /**
     * The trimmed URL when it has the expected shape. Otherwise fails without the value or a cause in the message: the
     * Cloudinary SDK's own parse error quotes the whole URL, secret included, into the startup log.
     */
    static String checkedCloudinaryUrl(String url) {
        String trimmed = url.trim();
        if (!CLOUDINARY_URL.matcher(trimmed).matches()) {
            throw new IllegalStateException("CLOUDINARY_URL must look like cloudinary://<api_key>:<api_secret>@<cloud_name>"
                    + " using the real values from Cloudinary > Settings > API Keys (no angle brackets, spaces or"
                    + " CLOUDINARY_URL= prefix)");
        }
        return trimmed;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (cloudinaryEnabled()) {
            return;
        }
        Path publicDir = Path.of(properties.localDir()).toAbsolutePath().normalize().resolve("public");
        registry.addResourceHandler("/uploads/public/**")
                .addResourceLocations(publicDir.toUri().toString())
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }
}
