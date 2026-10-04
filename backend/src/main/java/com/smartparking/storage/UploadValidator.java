package com.smartparking.storage;

import com.smartparking.common.error.ApiException;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

public final class UploadValidator {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp");

    private UploadValidator() {}

    public static ValidatedUpload validate(MultipartFile file, UploadKind kind) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Please choose a file to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Files must be 5 MB or smaller");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("FILE_REQUIRED", "The file could not be read");
        }
        String type = FileTypes.detect(bytes);
        boolean allowed = type != null && (IMAGES.contains(type) || (kind == UploadKind.DOCUMENT && type.equals("application/pdf")));
        if (!allowed) {
            String expected = kind == UploadKind.IMAGE ? "JPG, PNG or WebP images" : "JPG, PNG, WebP or PDF files";
            throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Only " + expected + " are allowed");
        }
        return new ValidatedUpload(bytes, type, FileTypes.extension(type));
    }
}
