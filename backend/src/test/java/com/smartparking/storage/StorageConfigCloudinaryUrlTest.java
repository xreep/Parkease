package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageConfigCloudinaryUrlTest {

    @Test
    void acceptsAWellFormedUrl() {
        assertThat(StorageConfig.checkedCloudinaryUrl(" cloudinary://123456:AbC-d_e@my-cloud "))
                .isEqualTo("cloudinary://123456:AbC-d_e@my-cloud");
    }

    @Test
    void rejectsTemplatePlaceholdersWithoutEchoingTheSecret() {
        String bad = "cloudinary://<:111222333>:<:TopSecretValue>@cloud";
        assertThatThrownBy(() -> StorageConfig.checkedCloudinaryUrl(bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDINARY_URL")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TopSecretValue", "111222333"));
    }

    @Test
    void rejectsOtherMalformedValues() {
        for (String bad : new String[] {"CLOUDINARY_URL=cloudinary://a:b@c", "cloudinary://a@c", "https://a:b@c",
                "cloudinary://a:b c@d"}) {
            assertThatThrownBy(() -> StorageConfig.checkedCloudinaryUrl(bad)).as(bad)
                    .isInstanceOf(IllegalStateException.class).hasNoCause();
        }
    }
}
