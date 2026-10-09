package com.smartparking.admin.audit;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.ListingTestSupport.createListing;
import static com.smartparking.support.ListingTestSupport.makeComplete;
import static com.smartparking.support.ListingTestSupport.puneCityId;
import static com.smartparking.support.OwnerTestSupport.pdf;
import static com.smartparking.support.OwnerTestSupport.unverifiedOwner;
import static com.smartparking.support.OwnerTestSupport.verifiedOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartparking.location.CityRepository;
import com.smartparking.owner.OwnerProfileRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AdminAuditControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired OwnerProfileRepository profiles;
    @Autowired CityRepository cities;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminAuth(mvc, users, encoder, "audit-admin@example.com");
    }

    private ResultActions adminPost(String path, String json) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json == null ? "" : json));
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("select action, target_type, target_id, details from admin_actions order by id");
    }

    private Long submittedListing(String ownerEmail) throws Exception {
        String owner = verifiedOwner(mvc, users, profiles, ownerEmail);
        Long id = createListing(mvc, owner, puneCityId(cities));
        makeComplete(mvc, owner, id);
        mvc.perform(post("/api/v1/owner/listings/" + id + "/submit").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    @Test
    void ownerVerifyAndRejectAreAudited() throws Exception {
        for (String email : new String[] {"audit-o1@example.com", "audit-o2@example.com"}) {
            String owner = unverifiedOwner(mvc, email);
            mvc.perform(multipart("/api/v1/owner/verification").file(pdf()).param("documentType", "AADHAAR")
                    .header(HttpHeaders.AUTHORIZATION, owner)).andExpect(status().isOk());
        }
        Long first = users.findByEmail("audit-o1@example.com").orElseThrow().getId();
        Long second = users.findByEmail("audit-o2@example.com").orElseThrow().getId();
        adminPost("/api/v1/admin/owners/" + first + "/verify", null).andExpect(status().isOk());
        adminPost("/api/v1/admin/owners/" + second + "/reject", "{\"reason\":\"Blurry document\"}")
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("action", "OWNER_VERIFIED").containsEntry("target_type", "OWNER")
                .containsEntry("target_id", first);
        assertThat(rows.get(1)).containsEntry("action", "OWNER_REJECTED").containsEntry("target_id", second);
        assertThat((String) rows.get(1).get("details")).contains("Blurry document");
    }

    @Test
    void listingApproveAndRejectAreAudited() throws Exception {
        Long approved = submittedListing("audit-l1@example.com");
        Long rejected = submittedListing("audit-l2@example.com");
        adminPost("/api/v1/admin/listings/" + approved + "/approve", null).andExpect(status().isOk());
        adminPost("/api/v1/admin/listings/" + rejected + "/reject", "{\"reason\":\"Photos too dark\"}")
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("action", "LISTING_APPROVED").containsEntry("target_type", "LISTING")
                .containsEntry("target_id", approved);
        assertThat(rows.get(1)).containsEntry("action", "LISTING_REJECTED").containsEntry("target_id", rejected);
        assertThat((String) rows.get(1).get("details")).contains("Photos too dark");
    }

    @Test
    void aFailedActionLeavesNoAuditRow() throws Exception {
        adminPost("/api/v1/admin/listings/999999/approve", null).andExpect(status().isNotFound());
        assertThat(rows()).isEmpty();
    }

    @Test
    void listsNewestFirstWithFiltersAndPaging() throws Exception {
        Long a = submittedListing("audit-f1@example.com");
        Long b = submittedListing("audit-f2@example.com");
        adminPost("/api/v1/admin/listings/" + a + "/approve", null).andExpect(status().isOk());
        adminPost("/api/v1/admin/listings/" + b + "/reject", "{\"reason\":\"No\"}").andExpect(status().isOk());
        String o = unverifiedOwner(mvc, "audit-f3@example.com");
        mvc.perform(multipart("/api/v1/owner/verification").file(pdf()).param("documentType", "AADHAAR")
                .header(HttpHeaders.AUTHORIZATION, o)).andExpect(status().isOk());
        Long ownerId = users.findByEmail("audit-f3@example.com").orElseThrow().getId();
        adminPost("/api/v1/admin/owners/" + ownerId + "/verify", null).andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/audit").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].action").value("OWNER_VERIFIED"))
                .andExpect(jsonPath("$.content[0].adminName").value("Asha Test"))
                .andExpect(jsonPath("$.content[0].targetType").value("OWNER"))
                .andExpect(jsonPath("$.content[0].targetId").value(ownerId))
                .andExpect(jsonPath("$.content[0].createdAt").exists())
                .andExpect(jsonPath("$.content[2].action").value("LISTING_APPROVED"));
        mvc.perform(get("/api/v1/admin/audit?targetType=LISTING").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("LISTING_REJECTED"));
        mvc.perform(get("/api/v1/admin/audit?action=LISTING_APPROVED").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].targetId").value(a))
                .andExpect(jsonPath("$.content[0].details").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/v1/admin/audit?action=LISTING_APPROVED&targetType=OWNER")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/admin/audit?page=1&size=2").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/admin/audit?size=500").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void onlyAdminsCanReadTheLog() throws Exception {
        String driver = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "audit-driver@example.com", "DRIVER")));
        mvc.perform(get("/api/v1/admin/audit").header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized());
    }
}
