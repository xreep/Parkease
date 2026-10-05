package com.smartparking.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CloudinaryFileStorage implements FileStorage {
    private final Cloudinary cloudinary;
    private final Clock clock;

    public CloudinaryFileStorage(Cloudinary cloudinary, Clock clock) {
        this.cloudinary = cloudinary;
        this.clock = clock;
    }

    @Override
    public StoredFile storePublic(ValidatedUpload upload, String folder) {
        Map<?, ?> r = upload(upload, folder, "upload");
        return new StoredFile(key("upload", r), (String) r.get("secure_url"), upload.contentType(), upload.bytes().length);
    }

    @Override
    public StoredFile storePrivate(ValidatedUpload upload, String folder) {
        Map<?, ?> r = upload(upload, folder, "private");
        return new StoredFile(key("private", r), null, upload.contentType(), upload.bytes().length);
    }

    @Override
    public void delete(String key) {
        Parsed p = parse(key);
        if (p == null) return;
        try {
            cloudinary.uploader().destroy(p.publicId(), ObjectUtils.asMap("resource_type", "image", "type", p.type()));
        } catch (Exception e) {
            log.warn("Could not delete Cloudinary asset {}", key, e);
        }
    }

    @Override
    public SignedUrl privateUrl(String key, Duration ttl) {
        Parsed p = parse(key);
        if (p == null || !p.type().equals("private")) throw new IllegalArgumentException("Not a private Cloudinary key");
        Instant expiresAt = clock.instant().plus(ttl);
        try {
            String url = cloudinary.privateDownload(p.publicId(), p.format(), ObjectUtils.asMap(
                    "resource_type", "image", "type", "private", "expires_at", expiresAt.getEpochSecond()));
            return new SignedUrl(url, expiresAt);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign Cloudinary URL", e);
        }
    }

    @Override
    public String publicUrl(String key) {
        Parsed p = parse(key);
        if (p == null || !p.type().equals("upload")) throw new IllegalArgumentException("Not a public Cloudinary key");
        return cloudinary.url().secure(true).generate(p.publicId() + "." + p.format());
    }

    private Map<?, ?> upload(ValidatedUpload upload, String folder, String type) {
        try {
            return cloudinary.uploader().upload(upload.bytes(), ObjectUtils.asMap(
                    "folder", "parkease/" + folder, "resource_type", "image", "type", type));
        } catch (IOException e) {
            throw new UncheckedIOException("Upload to Cloudinary failed", e);
        }
    }

    private static String key(String type, Map<?, ?> r) {
        return "cloudinary/" + type + "/" + r.get("public_id") + "." + r.get("format");
    }

    record Parsed(String type, String publicId, String format) {}

    static Parsed parse(String key) {
        if (key == null || !key.startsWith("cloudinary/")) return null;
        String rest = key.substring("cloudinary/".length());
        int slash = rest.indexOf('/');
        int dot = rest.lastIndexOf('.');
        if (slash < 0 || dot < slash) return null;
        return new Parsed(rest.substring(0, slash), rest.substring(slash + 1, dot), rest.substring(dot + 1));
    }
}
