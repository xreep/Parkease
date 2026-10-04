package com.smartparking.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalFileStorage implements FileStorage {
    private static final Pattern SAFE_KEY =
            Pattern.compile("^local/(public|private)/[a-z0-9-]+/[0-9a-f-]{36}\\.(jpg|png|webp|pdf)$");

    private static final Pattern SAFE_FOLDER = Pattern.compile("[a-z0-9-]+");

    private final Path root;
    private final String publicBaseUrl;
    private final UrlSigner signer;
    private final Clock clock;

    public LocalFileStorage(Path root, String publicBaseUrl, UrlSigner signer, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.signer = signer;
        this.clock = clock;
    }

    public Path root() { return root; }

    @Override
    public StoredFile storePublic(ValidatedUpload upload, String folder) {
        String key = write("public", folder, upload);
        String url = publicBaseUrl + "/uploads/" + key.substring("local/".length());
        return new StoredFile(key, url, upload.contentType(), upload.bytes().length);
    }

    @Override
    public StoredFile storePrivate(ValidatedUpload upload, String folder) {
        return new StoredFile(write("private", folder, upload), null, upload.contentType(), upload.bytes().length);
    }

    @Override
    public void delete(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) return;
        try {
            Files.deleteIfExists(root.resolve(key.substring("local/".length())).normalize());
        } catch (IOException e) {
            log.warn("Could not delete stored file {}", key, e);
        }
    }

    @Override
    public SignedUrl privateUrl(String key, Duration ttl) {
        if (key == null || !key.startsWith("local/private/")) {
            throw new IllegalArgumentException("Not a private local key");
        }
        Instant expiresAt = clock.instant().plus(ttl);
        long expires = expiresAt.getEpochSecond();
        String url = publicBaseUrl + "/api/v1/files/private?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&expires=" + expires + "&sig=" + signer.sign(key, expires);
        return new SignedUrl(url, expiresAt);
    }

    /** Path of a private file for a safe key, or empty. */
    public Optional<Path> resolvePrivate(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches() || !key.startsWith("local/private/")) return Optional.empty();
        Path path = root.resolve(key.substring("local/".length())).normalize();
        return path.startsWith(root) && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private String write(String visibility, String folder, ValidatedUpload upload) {
        if (folder == null || !SAFE_FOLDER.matcher(folder).matches()) {
            throw new IllegalArgumentException("Invalid folder name");
        }
        String key = "local/" + visibility + "/" + folder + "/" + UUID.randomUUID() + "." + upload.extension();
        Path path = root.resolve(key.substring("local/".length()));
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, upload.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file", e);
        }
        return key;
    }
}
