package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudinary.Cloudinary;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class CloudinaryFileStorageTest {

    private final CloudinaryFileStorage storage =
            new CloudinaryFileStorage(new Cloudinary("cloudinary://key:secret@demo"), Clock.systemUTC());

    @Test
    void publicUrlIsBuiltFromTheKey() {
        assertThat(storage.publicUrl("cloudinary/upload/parkease/listing-photos/abc.png"))
                .startsWith("https://res.cloudinary.com/demo/image/upload/")
                .contains("parkease/listing-photos/abc.png");
    }

    @Test
    void publicUrlRejectsKeysFromElsewhereOrPrivateOnes() {
        assertThatThrownBy(() -> storage.publicUrl("local/public/listing-photos/x.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.publicUrl("cloudinary/private/parkease/owner-documents/x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
