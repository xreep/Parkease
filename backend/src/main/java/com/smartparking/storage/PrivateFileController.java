package com.smartparking.storage;

import com.smartparking.common.error.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves locally stored private files (owner documents) to holders of a valid, unexpired signed URL. */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class PrivateFileController {

    private final FileStorage storage;
    private final UrlSigner signer;
    private final Clock clock;

    @GetMapping("/private")
    ResponseEntity<byte[]> download(@RequestParam(required = false) String key,
                                    @RequestParam(required = false) Long expires,
                                    @RequestParam(required = false) String sig) {
        if (!(storage instanceof LocalFileStorage local)) {
            throw ApiException.notFound("File not found");
        }
        if (key == null || expires == null || !signer.verify(key, expires, sig, clock.instant())) {
            throw ApiException.forbidden("INVALID_SIGNATURE", "This link is invalid or has expired");
        }
        Path path = local.resolvePrivate(key).orElseThrow(() -> ApiException.notFound("File not found"));
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String type = FileTypes.detect(bytes);
        return ResponseEntity.ok()
                .contentType(type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(bytes);
    }
}
