package com.smartparking.notification;

import static com.smartparking.support.AuthTestSupport.accessToken;
import static com.smartparking.support.AuthTestSupport.bearer;
import static com.smartparking.support.AuthTestSupport.register;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.support.IntegrationTest;
import com.smartparking.user.User;
import com.smartparking.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class NotificationControllerTest {

    static final String URL = "/api/v1/notifications";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired Notifier notifier;
    @Autowired NotificationRepository notifications;

    String driverAuth;
    String ownerAuth;
    User driver;
    User owner;

    @BeforeEach
    void setUp() throws Exception {
        driverAuth = bearer(accessToken(register(mvc, "notif-driver@example.com", "DRIVER")));
        ownerAuth = bearer(accessToken(register(mvc, "notif-owner@example.com", "OWNER")));
        driver = users.findByEmail("notif-driver@example.com").orElseThrow();
        owner = users.findByEmail("notif-owner@example.com").orElseThrow();
    }

    private long add(User user, String title) {
        notifier.notify(user, NotificationType.BOOKING_CONFIRMED, title, "Body of " + title, "/driver/bookings/1", null);
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(user.getId(),
                org.springframework.data.domain.PageRequest.of(0, 1)).getContent().getFirst().getId();
    }

    private ResultActions getAs(String auth, String path) throws Exception {
        return mvc.perform(get(URL + path).header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions postAs(String auth, String path) throws Exception {
        return mvc.perform(post(URL + path).header(HttpHeaders.AUTHORIZATION, auth));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post(URL + "/read-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsOwnNotificationsNewestFirstWithTheDtoShape() throws Exception {
        long first = add(driver, "First");
        long second = add(driver, "Second");
        add(owner, "Not yours");

        getAs(driverAuth, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[0].type").value("BOOKING_CONFIRMED"))
                .andExpect(jsonPath("$.content[0].title").value("Second"))
                .andExpect(jsonPath("$.content[0].body").value("Body of Second"))
                .andExpect(jsonPath("$.content[0].link").value("/driver/bookings/1"))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.totalElements").value(2));
        getAs(ownerAuth, "").andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Not yours"));
    }

    @Test
    void pagesAndCapsThePageSizeAtFifty() throws Exception {
        for (int i = 0; i < 3; i++) {
            add(driver, "N" + i);
        }
        getAs(driverAuth, "?page=0&size=2").andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].title").value("N2"));
        getAs(driverAuth, "?page=1&size=2").andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("N0"));
        getAs(driverAuth, "?size=500").andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void unreadCountTracksReads() throws Exception {
        long a = add(driver, "A");
        add(driver, "B");
        add(owner, "C");
        getAs(driverAuth, "/unread-count").andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));

        postAs(driverAuth, "/" + a + "/read").andExpect(status().isNoContent());
        getAs(driverAuth, "/unread-count").andExpect(jsonPath("$.count").value(1));
        getAs(ownerAuth, "/unread-count").andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void markReadIsIdempotentAndKeepsTheFirstReadTime() throws Exception {
        long a = add(driver, "A");
        postAs(driverAuth, "/" + a + "/read").andExpect(status().isNoContent());
        java.time.Instant firstRead = notifications.findById(a).orElseThrow().getReadAt();
        postAs(driverAuth, "/" + a + "/read").andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(notifications.findById(a).orElseThrow().getReadAt())
                .isEqualTo(firstRead).isNotNull();
        getAs(driverAuth, "").andExpect(jsonPath("$.content[0].read").value(true));
    }

    @Test
    void anotherUsersNotificationIsNotFound() throws Exception {
        long mine = add(driver, "Mine");

        postAs(ownerAuth, "/" + mine + "/read").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        postAs(ownerAuth, "/999999999/read").andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(notifications.findById(mine).orElseThrow().getReadAt()).isNull();
    }

    @Test
    void readAllMarksOnlyTheCallersNotifications() throws Exception {
        add(driver, "A");
        add(driver, "B");
        add(owner, "C");

        postAs(driverAuth, "/read-all").andExpect(status().isNoContent());

        getAs(driverAuth, "/unread-count").andExpect(jsonPath("$.count").value(0));
        getAs(ownerAuth, "/unread-count").andExpect(jsonPath("$.count").value(1));
        String json = getAs(driverAuth, "").andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat((Iterable<?>) JsonPath.read(json, "$.content[?(@.read == false)]"))
                .isEmpty();
        postAs(driverAuth, "/read-all").andExpect(status().isNoContent());
    }
}
