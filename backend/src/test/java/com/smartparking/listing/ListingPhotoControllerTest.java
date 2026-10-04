package com.smartparking.listing;

import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.gif;
import static com.smartparking.support.OwnerTestSupport.png;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.location.CityRepository;
import com.smartparking.storage.FileStorage;
import com.smartparking.storage.LocalFileStorage;
import com.smartparking.support.IntegrationTest;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ListingPhotoControllerTest {

    @Autowired MockMvc mvc;
    @Autowired CityRepository cities;
    @Autowired ListingPhotoRepository photoRepository;
    @Autowired FileStorage storage;

    String auth;
    Long listingId;

    @BeforeEach
    void setUp() throws Exception {
        auth = unverifiedOwner(mvc, "photos@example.com");
        listingId = createListing(mvc, auth, puneCityId(cities));
    }

    private String url() {
        return "/api/v1/owner/listings/" + listingId + "/photos";
    }

    private long upload() throws Exception {
        String body = mvc.perform(multipart(url()).file(png("p.png")).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void uploadsPhotoAndShowsItOnTheListing() throws Exception {
        mvc.perform(multipart(url()).file(png("p.png")).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(startsWith("http://localhost:8080/uploads/public/listing-photos/")))
                .andExpect(jsonPath("$.sortOrder").value(0));
        mvc.perform(get("/api/v1/owner/listings/" + listingId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.photos.length()").value(1));
    }

    @Test
    void rejectsDisguisedGif() throws Exception {
        mvc.perform(multipart(url()).file(gif()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void requiresAFile() throws Exception {
        mvc.perform(multipart(url()).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REQUIRED"));
    }

    @Test
    void enforcesPhotoLimit() throws Exception {
        for (int i = 0; i < 8; i++) {
            upload();
        }
        mvc.perform(multipart(url()).file(png("p.png")).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHOTO_LIMIT"));
        assertThat(photoRepository.countByListingId(listingId)).isEqualTo(8);
    }

    @Test
    void reordersPhotosAndCoverFollowsFirst() throws Exception {
        long a = upload();
        long b = upload();
        long c = upload();
        mvc.perform(put(url() + "/order").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"photoIds\":[%d,%d,%d]}".formatted(c, b, a)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(c))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[2].id").value(a));
        String detail = mvc.perform(get("/api/v1/owner/listings/" + listingId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.photos[0].id").value(c))
                .andReturn().getResponse().getContentAsString();
        String coverUrl = JsonPath.read(detail, "$.photos[0].url");
        mvc.perform(get("/api/v1/owner/listings").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.content[0].coverPhotoUrl").value(coverUrl));
    }

    @Test
    void rejectsOrderThatIsNotExactlyThePhotoSet() throws Exception {
        long a = upload();
        long b = upload();
        long c = upload();
        for (String ids : List.of(a + "," + b, a + "," + b + "," + 999999, a + "," + a + "," + b, a + "," + b + "," + c + "," + c)) {
            mvc.perform(put(url() + "/order").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":[" + ids + "]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PHOTO_ORDER"));
        }
    }

    @Test
    void deletingMiddlePhotoRenumbersTheRest() throws Exception {
        long a = upload();
        long b = upload();
        long c = upload();
        mvc.perform(delete(url() + "/" + b).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/owner/listings/" + listingId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.photos.length()").value(2))
                .andExpect(jsonPath("$.photos[0].id").value(a))
                .andExpect(jsonPath("$.photos[0].sortOrder").value(0))
                .andExpect(jsonPath("$.photos[1].id").value(c))
                .andExpect(jsonPath("$.photos[1].sortOrder").value(1));
    }

    @Test
    void deletingAPhotoRemovesTheStoredFile() throws Exception {
        long id = upload();
        String key = photoRepository.findById(id).orElseThrow().getStorageKey();
        LocalFileStorage local = (LocalFileStorage) storage;
        var path = local.root().resolve(key.substring("local/".length()));
        assertThat(Files.exists(path)).isTrue();
        mvc.perform(delete(url() + "/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void photoOfAnotherListingOrOwnerIs404() throws Exception {
        long id = upload();
        Long other = createListing(mvc, auth, puneCityId(cities));
        mvc.perform(delete("/api/v1/owner/listings/" + other + "/photos/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
        String intruder = unverifiedOwner(mvc, "intruder-photos@example.com");
        mvc.perform(delete(url() + "/" + id).header(HttpHeaders.AUTHORIZATION, intruder))
                .andExpect(status().isNotFound());
        mvc.perform(multipart(url()).file(png("p.png")).header(HttpHeaders.AUTHORIZATION, intruder))
                .andExpect(status().isNotFound());
    }
}
