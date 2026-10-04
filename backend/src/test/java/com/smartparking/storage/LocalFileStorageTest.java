package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path dir;

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0};
    Clock clock = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);
    UrlSigner signer = new UrlSigner("dGVzdC1vbmx5LWp3dC1zZWNyZXQtc21hcnQtcGFya2luZy1wbGF0Zm9ybS0yMDI2");

    LocalFileStorage storage() {
        return new LocalFileStorage(dir, "http://localhost:8080", signer, clock);
    }

    @Test
    void storesPublicFileWithServableUrl() throws Exception {
        StoredFile f = storage().storePublic(new ValidatedUpload(PNG, "image/png", "png"), "listing-photos");

        assertThat(f.key()).matches("local/public/listing-photos/[0-9a-f-]{36}\\.png");
        assertThat(f.url()).isEqualTo("http://localhost:8080/uploads/public/listing-photos/"
                + f.key().substring("local/public/listing-photos/".length()));
        assertThat(Files.readAllBytes(dir.resolve(f.key().substring("local/".length())))).isEqualTo(PNG);
    }

    @Test
    void storesPrivateFileWithoutPublicUrlAndSignsDownloadUrl() {
        LocalFileStorage s = storage();
        StoredFile f = s.storePrivate(new ValidatedUpload(PNG, "image/png", "png"), "owner-documents");

        assertThat(f.url()).isNull();
        FileStorage.SignedUrl signed = s.privateUrl(f.key(), Duration.ofMinutes(5));
        assertThat(signed.url()).startsWith("http://localhost:8080/api/v1/files/private?key=");
        assertThat(signed.expiresAt()).isEqualTo(Instant.parse("2026-10-05T10:05:00Z"));
    }

    @Test
    void deleteRemovesFileAndIgnoresUnknownKeys() throws Exception {
        LocalFileStorage s = storage();
        StoredFile f = s.storePublic(new ValidatedUpload(PNG, "image/png", "png"), "listing-photos");

        s.delete(f.key());
        s.delete(null);
        s.delete("cloudinary/upload/abc.png");
        s.delete("local/public/../../etc/passwd");

        // A traversal key whose target resolves inside the root must not delete the sentinel file.
        Path sentinel = dir.resolve("private/secret.txt");
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, "keep me");
        s.delete("local/public/../private/secret.txt");
        assertThat(Files.exists(sentinel)).isTrue();

        assertThat(Files.exists(dir.resolve(f.key().substring("local/".length())))).isFalse();
    }

    @Test
    void resolvesOnlySafePrivateKeys() {
        LocalFileStorage s = storage();
        StoredFile pub = s.storePublic(new ValidatedUpload(PNG, "image/png", "png"), "listing-photos");
        StoredFile priv = s.storePrivate(new ValidatedUpload(PNG, "image/png", "png"), "owner-documents");

        // UUID-shaped public key: exists on disk but is not private.
        assertThat(s.resolvePrivate(pub.key())).isEmpty();
        assertThat(s.resolvePrivate(priv.key())).isPresent();
        assertThat(s.resolvePrivate("local/private/../public/x.png")).isEmpty();
        assertThat(s.resolvePrivate("local/public/listing-photos/x.png")).isEmpty();
        assertThat(s.resolvePrivate(null)).isEmpty();
    }

    @Test
    void rejectsUnsafeFoldersAndNonPrivateKeysForSignedUrls() {
        LocalFileStorage s = storage();
        ValidatedUpload upload = new ValidatedUpload(PNG, "image/png", "png");
        assertThatThrownBy(() -> s.storePublic(upload, "../etc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.storePrivate(upload, "Owner Docs")).isInstanceOf(IllegalArgumentException.class);

        StoredFile pub = s.storePublic(upload, "listing-photos");
        assertThatThrownBy(() -> s.privateUrl(pub.key(), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.privateUrl(null, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
