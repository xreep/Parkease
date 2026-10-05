package com.smartparking.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.support.IntegrationTest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PrivateFileControllerTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8};

    @Autowired MockMvc mvc;
    @Autowired FileStorage storage;
    @Autowired UrlSigner signer;
    @Autowired Clock clock;

    private static String pathAndQuery(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + "?" + uri.getRawQuery();
    }

    @Test
    void servesPrivateFileWithValidSignatureAndRejectsBadOnes() throws Exception {
        StoredFile stored = storage.storePrivate(new ValidatedUpload(PNG, "image/png", "png"), "owner-documents");
        String url = pathAndQuery(storage.privateUrl(stored.key(), Duration.ofMinutes(5)).url());

        mvc.perform(get(URI.create(url)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        String tampered = url.replaceAll("sig=([^&]+)", "sig=$1x");
        mvc.perform(get(URI.create(tampered)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        long past = clock.instant().getEpochSecond() - 10;
        String expiredUrl = "/api/v1/files/private?key=" + URLEncoder.encode(stored.key(), StandardCharsets.UTF_8)
                + "&expires=" + past + "&sig=" + signer.sign(stored.key(), past);
        mvc.perform(get(URI.create(expiredUrl)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    }

    @Test
    void privateFilesAreNotReachableThroughTheStaticUploadsPath() throws Exception {
        StoredFile stored = storage.storePrivate(new ValidatedUpload(PNG, "image/png", "png"), "owner-documents");
        String file = stored.key().substring("local/private/owner-documents/".length());

        mvc.perform(get("/uploads/private/owner-documents/" + file)).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/public/../private/owner-documents/" + file))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void validlySignedUrlForPublicKeyIsNotFound() throws Exception {
        StoredFile stored = storage.storePublic(new ValidatedUpload(PNG, "image/png", "png"), "listing-photos");
        long expires = clock.instant().getEpochSecond() + 300;
        String url = "/api/v1/files/private?key=" + URLEncoder.encode(stored.key(), StandardCharsets.UTF_8)
                + "&expires=" + expires + "&sig=" + signer.sign(stored.key(), expires);

        mvc.perform(get(URI.create(url))).andExpect(status().isNotFound());
    }

    @Test
    void missingParametersAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/files/private"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        mvc.perform(get("/api/v1/files/private").param("key", "local/private/owner-documents/x.png"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicFilesAreServedWithoutAuth() throws Exception {
        StoredFile stored = storage.storePublic(new ValidatedUpload(PNG, "image/png", "png"), "listing-photos");
        String path = URI.create(stored.url()).getRawPath();
        assertThat(path).startsWith("/uploads/public/listing-photos/");

        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));
    }
}
