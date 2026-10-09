package com.smartparking.admin.locations;

import static com.smartparking.support.AdminTestSupport.adminAuth;
import static com.smartparking.support.ListingTestSupport.approvedListingAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.IntegrationTest;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.user.UserRepository;
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
class AdminLocationControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;

    String admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminAuth(mvc, users, encoder, "loc-admin@example.com");
    }

    private ResultActions adminGet(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin));
    }

    private ResultActions adminPost(String path, String json) throws Exception {
        return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions adminPatch(String path, String json) throws Exception {
        return mvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long stateId(String slug) {
        return jdbc.queryForObject("select id from states where slug = ?", Long.class, slug);
    }

    private long cityId(String slug) {
        return jdbc.queryForObject("select id from cities where slug = ?", Long.class, slug);
    }

    private static String cityJson(long stateId, String name, double lat, double lng, Integer tier) {
        return "{\"stateId\":%d,\"name\":\"%s\",\"lat\":%s,\"lng\":%s%s}".formatted(stateId, name, lat, lng,
                tier == null ? "" : ",\"tier\":" + tier);
    }

    // ---- states -------------------------------------------------------------------------------------------

    @Test
    void listsStatesWithCityCounts() throws Exception {
        adminGet("/api/v1/admin/states").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(36))
                .andExpect(jsonPath("$[?(@.slug=='maharashtra')].name").value("Maharashtra"))
                .andExpect(jsonPath("$[?(@.slug=='maharashtra')].code").value("MH"))
                .andExpect(jsonPath("$[?(@.slug=='maharashtra')].type").value("STATE"))
                .andExpect(jsonPath("$[?(@.slug=='maharashtra')].capitalName").value("Mumbai"))
                .andExpect(jsonPath("$[?(@.slug=='maharashtra')].citiesCount").value(
                        jdbc.queryForObject("select count(*) from cities c join states s on s.id = c.state_id "
                                + "where s.slug = 'maharashtra'", Integer.class)));
    }

    @Test
    void createsAndEditsAState() throws Exception {
        String body = adminPost("/api/v1/admin/states",
                "{\"name\":\"  Test Land \",\"code\":\"tz\",\"type\":\"UT\",\"capitalName\":\"Testville\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Land"))
                .andExpect(jsonPath("$.code").value("TZ"))
                .andExpect(jsonPath("$.slug").value("test-land"))
                .andExpect(jsonPath("$.type").value("UT"))
                .andExpect(jsonPath("$.capitalName").value("Testville"))
                .andExpect(jsonPath("$.citiesCount").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();

        adminPatch("/api/v1/admin/states/" + id, "{\"capitalName\":\"New Testville\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.capitalName").value("New Testville"))
                .andExpect(jsonPath("$.name").value("Test Land"));
        adminPatch("/api/v1/admin/states/" + id, "{\"name\":\"Testland\",\"type\":\"STATE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Testland")).andExpect(jsonPath("$.slug").value("testland"))
                .andExpect(jsonPath("$.type").value("STATE"));
        assertThat(jdbc.queryForList("select action || ' ' || target_type || ' ' || target_id from admin_actions "
                + "order by id", String.class))
                .containsExactly("STATE_CREATED STATE " + id, "STATE_UPDATED STATE " + id, "STATE_UPDATED STATE " + id);
    }

    @Test
    void stateValidationAndConflicts() throws Exception {
        adminPost("/api/v1/admin/states", "{\"name\":\"X\",\"code\":\"TZ\",\"type\":\"UT\",\"capitalName\":\"Testville\"}")
                .andExpect(status().isBadRequest());
        adminPost("/api/v1/admin/states", "{\"name\":\"Test Land\",\"code\":\"T\",\"type\":\"UT\",\"capitalName\":\"Testville\"}")
                .andExpect(status().isBadRequest());
        adminPost("/api/v1/admin/states", "{\"name\":\"Test Land\",\"code\":\"TZ\",\"capitalName\":\"Testville\"}")
                .andExpect(status().isBadRequest());
        adminPost("/api/v1/admin/states",
                "{\"name\":\"Maharashtra\",\"code\":\"TZ\",\"type\":\"STATE\",\"capitalName\":\"Testville\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
        adminPost("/api/v1/admin/states",
                "{\"name\":\"Test Land\",\"code\":\"MH\",\"type\":\"STATE\",\"capitalName\":\"Testville\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODE_TAKEN"));
        adminPatch("/api/v1/admin/states/" + stateId("goa"), "{\"name\":\"Gujarat\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
        adminPatch("/api/v1/admin/states/999999", "{\"name\":\"Nowhere\"}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
    }

    // ---- cities -------------------------------------------------------------------------------------------

    @Test
    void listsCitiesWithFilters() throws Exception {
        long mh = stateId("maharashtra");
        long pune = cityId("pune");
        ListingTestSupport.createListing(mvc, AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "loc-owner@example.com", "OWNER"))), pune);
        jdbc.update("update cities set active = false where slug = 'nagpur'");

        adminGet("/api/v1/admin/cities?stateId=" + mh + "&q=PUN").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pune))
                .andExpect(jsonPath("$.content[0].stateId").value(mh))
                .andExpect(jsonPath("$.content[0].stateName").value("Maharashtra"))
                .andExpect(jsonPath("$.content[0].name").value("Pune"))
                .andExpect(jsonPath("$.content[0].slug").value("pune"))
                .andExpect(jsonPath("$.content[0].lat").value(18.5204))
                .andExpect(jsonPath("$.content[0].lng").value(73.8567))
                .andExpect(jsonPath("$.content[0].capital").value(false))
                .andExpect(jsonPath("$.content[0].tier").value(1))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[0].listingsCount").value(1));
        adminGet("/api/v1/admin/cities?stateId=" + mh + "&active=false").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("nagpur"));
        adminGet("/api/v1/admin/cities?stateId=" + mh + "&active=true")
                .andExpect(jsonPath("$.content[*].slug", not(hasItem("nagpur"))));
        adminGet("/api/v1/admin/cities?size=5&page=1").andExpect(jsonPath("$.content.length()").value(5));
    }

    @Test
    void createsACityWithADerivedSlug() throws Exception {
        long mh = stateId("maharashtra");
        String body = adminPost("/api/v1/admin/cities", cityJson(mh, "  Nashik Road  ", 19.95, 73.78, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Nashik Road"))
                .andExpect(jsonPath("$.slug").value("nashik-road"))
                .andExpect(jsonPath("$.stateId").value(mh))
                .andExpect(jsonPath("$.stateName").value("Maharashtra"))
                .andExpect(jsonPath("$.tier").value(3))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.capital").value(false))
                .andExpect(jsonPath("$.listingsCount").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        adminPost("/api/v1/admin/cities", ("{\"stateId\":%d,\"name\":\"Kūrla Éast\",\"lat\":19.07,\"lng\":72.87,"
                + "\"tier\":2,\"capital\":true,\"active\":false}").formatted(mh))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("kurla-east"))
                .andExpect(jsonPath("$.tier").value(2)).andExpect(jsonPath("$.capital").value(true))
                .andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.queryForObject("select action || ' ' || target_type || ' ' || target_id from admin_actions "
                + "order by id limit 1", String.class)).isEqualTo("CITY_CREATED CITY " + id);
    }

    @Test
    void citySlugsAreUniquePerStateOnly() throws Exception {
        long mh = stateId("maharashtra");
        adminPost("/api/v1/admin/cities", cityJson(mh, "Pune", 18.52, 73.85, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
        adminPost("/api/v1/admin/cities", cityJson(mh, "PUNE!", 18.52, 73.85, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
        // the same name in another state is fine
        adminPost("/api/v1/admin/cities", cityJson(stateId("goa"), "Pune", 15.5, 73.8, null))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("pune"));
    }

    @Test
    void cityValidation() throws Exception {
        long mh = stateId("maharashtra");
        String[] bad = {
                cityJson(mh, "X", 19, 73, null),
                cityJson(mh, "x".repeat(101), 19, 73, null),
                cityJson(mh, "Valid Name", 5.9, 73, null),
                cityJson(mh, "Valid Name", 38.1, 73, null),
                cityJson(mh, "Valid Name", 19, 67.9, null),
                cityJson(mh, "Valid Name", 19, 98.1, null),
                cityJson(mh, "Valid Name", 19, 73, 0),
                cityJson(mh, "Valid Name", 19, 73, 4),
                "{\"stateId\":%d,\"name\":\"Valid Name\",\"lng\":73}".formatted(mh),
                "{\"name\":\"Valid Name\",\"lat\":19,\"lng\":73}",
                "{\"stateId\":%d,\"name\":\"!!\",\"lat\":19,\"lng\":73}".formatted(mh)};
        for (String json : bad) {
            adminPost("/api/v1/admin/cities", json).andExpect(status().isBadRequest());
        }
        adminPost("/api/v1/admin/cities", cityJson(999999L, "Valid Name", 19, 73, null)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from admin_actions", Integer.class)).isZero();
        // the boundaries themselves are fine
        adminPost("/api/v1/admin/cities", cityJson(mh, "Corner One", 6, 68, 1)).andExpect(status().isCreated());
        adminPost("/api/v1/admin/cities", cityJson(mh, "Corner Two", 38, 98, 3)).andExpect(status().isCreated());
    }

    @Test
    void editsACity() throws Exception {
        long pune = cityId("pune");
        adminPatch("/api/v1/admin/cities/" + pune, "{\"tier\":2,\"lat\":18.6,\"lng\":73.9,\"capital\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value(2)).andExpect(jsonPath("$.lat").value(18.6))
                .andExpect(jsonPath("$.lng").value(73.9)).andExpect(jsonPath("$.capital").value(true))
                .andExpect(jsonPath("$.name").value("Pune")).andExpect(jsonPath("$.slug").value("pune"));
        adminPatch("/api/v1/admin/cities/" + pune, "{\"name\":\"Pune City\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pune City")).andExpect(jsonPath("$.slug").value("pune-city"));
        adminPatch("/api/v1/admin/cities/" + pune, "{\"name\":\"Nagpur\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLUG_TAKEN"));
        adminPatch("/api/v1/admin/cities/" + pune, "{\"tier\":9}").andExpect(status().isBadRequest());
        adminPatch("/api/v1/admin/cities/" + pune, "{\"lat\":50}").andExpect(status().isBadRequest());
        adminPatch("/api/v1/admin/cities/999999", "{\"tier\":2}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForList("select action from admin_actions", String.class))
                .containsExactly("CITY_UPDATED", "CITY_UPDATED");
    }

    @Test
    void deactivatingACityHidesItFromPublicListsButKeepsItsListings() throws Exception {
        long pune = cityId("pune");
        String owner = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "loc-owner2@example.com", "OWNER")));
        Long listing = approvedListingAt(mvc, owner, listings, pune, "Pune Spot", 18.5204, 73.8567, 30);
        mvc.perform(get("/api/v1/cities?q=Pune")).andExpect(jsonPath("$[*].slug", hasItem("pune")));
        mvc.perform(get("/api/v1/states/maharashtra")).andExpect(jsonPath("$.cities[*].slug", hasItem("pune")));

        adminPatch("/api/v1/admin/cities/" + pune, "{\"active\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.listingsCount").value(1));

        mvc.perform(get("/api/v1/cities?q=Pune")).andExpect(jsonPath("$[*].slug", not(hasItem("pune"))));
        mvc.perform(get("/api/v1/states/maharashtra"))
                .andExpect(jsonPath("$.cities[*].slug", not(hasItem("pune"))));
        mvc.perform(get("/api/v1/states")).andExpect(jsonPath("$[?(@.slug=='maharashtra')].cityCount").value(
                jdbc.queryForObject("select count(*) from cities c join states s on s.id = c.state_id "
                        + "where s.slug = 'maharashtra' and c.active", Integer.class)));
        // the listing itself stays live
        mvc.perform(get("/api/v1/listings/" + listing)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/search?lat=18.5204&lng=73.8567"))
                .andExpect(jsonPath("$.content[*].id", hasItem(listing.intValue())));

        adminPatch("/api/v1/admin/cities/" + pune, "{\"active\":true}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/cities?q=Pune")).andExpect(jsonPath("$[*].slug", hasItem("pune")));
    }

    @Test
    void onlyAdminsCanManageLocations() throws Exception {
        String driver = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, "loc-driver@example.com", "DRIVER")));
        mvc.perform(get("/api/v1/admin/states").header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/cities").header(HttpHeaders.AUTHORIZATION, driver))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/cities").header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON).content(cityJson(1, "Valid Name", 19, 73, null)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/cities/1").header(HttpHeaders.AUTHORIZATION, driver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tier\":2}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/states")).andExpect(status().isUnauthorized());
    }
}
