package com.smartparking.review;

import static com.smartparking.support.BookingApiSupport.bookingId;
import static com.smartparking.support.BookingApiSupport.driverWithVehicle;
import static com.smartparking.support.BookingApiSupport.payOk;
import static com.smartparking.support.BookingApiSupport.reserveOk;
import static com.smartparking.support.BookingApiSupport.tomorrowAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.smartparking.email.EmailMessage;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.CityRepository;
import com.smartparking.support.AuthTestSupport;
import com.smartparking.support.BookingApiSupport.Driver;
import com.smartparking.support.CommittedIntegrationTest;
import com.smartparking.support.DatabaseCleaner;
import com.smartparking.support.ListingTestSupport;
import com.smartparking.support.RecordingEmailSender;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;

@CommittedIntegrationTest
class ReviewFlowTest {

    private static final String OWNER_EMAIL = "rv-owner@example.com";
    private static final String OTHER_OWNER_EMAIL = "rv-owner2@example.com";
    private static final String DRIVER_EMAIL = "rv-driver@example.com";
    private static final String DRIVER2_EMAIL = "rv-driver2@example.com";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CityRepository cities;
    @Autowired ParkingListingRepository listings;
    @Autowired RecordingEmailSender emails;
    @Autowired PlatformTransactionManager txManager;

    private String ownerAuth;
    private String otherOwnerAuth;
    private Long listingId;
    private Driver driver;
    private Driver driver2;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        ownerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OWNER_EMAIL, "OWNER")));
        otherOwnerAuth = AuthTestSupport.bearer(AuthTestSupport.accessToken(
                AuthTestSupport.register(mvc, OTHER_OWNER_EMAIL, "OWNER")));
        listingId = ListingTestSupport.approvedListingAt(mvc, ownerAuth, listings,
                ListingTestSupport.puneCityId(cities), "Review Spot", 18.5204, 73.8567, 30);
        driver = driverWithVehicle(mvc, DRIVER_EMAIL);
        driver2 = driverWithVehicle(mvc, DRIVER2_EMAIL);
        emails.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    /** A paid booking made COMPLETED, ending {@code endedAgo} ago. */
    private long completed(Driver who, int startHour, Duration endedAgo) throws Exception {
        Instant start = tomorrowAt(startHour);
        long id = bookingId(reserveOk(mvc, who.auth(), listingId, who.vehicleId(), start, start.plusSeconds(7200)));
        payOk(mvc, who.auth(), id);
        Instant end = Instant.now().minus(endedAgo);
        jdbc.update("update bookings set status = 'COMPLETED', start_time = ?, end_time = ?, completed_at = ? "
                + "where id = ?", Timestamp.from(end.minusSeconds(7200)), Timestamp.from(end), Timestamp.from(end), id);
        return id;
    }

    private long completed(Driver who, int startHour) throws Exception {
        return completed(who, startHour, Duration.ofHours(1));
    }

    private ResultActions review(Driver who, long bookingId, String json) throws Exception {
        return mvc.perform(post("/api/v1/bookings/" + bookingId + "/review")
                .header(HttpHeaders.AUTHORIZATION, who.auth()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long reviewOk(Driver who, long bookingId, int rating, String comment) throws Exception {
        String json = comment == null ? "{\"rating\":" + rating + "}"
                : "{\"rating\":" + rating + ",\"comment\":\"" + comment + "\"}";
        String body = review(who, bookingId, json).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions reply(String auth, long reviewId, String json) throws Exception {
        return mvc.perform(post("/api/v1/owner/reviews/" + reviewId + "/reply")
                .header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Map<String, Object> listingRow() {
        return jdbc.queryForMap("select avg_rating, review_count from parking_listings where id = ?", listingId);
    }

    // ---- posting ------------------------------------------------------------------------------------------

    @Test
    void driverReviewsACompletedBookingAndTheListingAggregatesFollow() throws Exception {
        long booking = completed(driver, 10);

        review(driver, booking, "{\"rating\":5,\"comment\":\"  Easy to find  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Easy to find"))
                .andExpect(jsonPath("$.authorName").value("Ravi K."))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.ownerReply").value(nullValue()))
                .andExpect(jsonPath("$.ownerRepliedAt").value(nullValue()));

        assertThat(((BigDecimal) listingRow().get("avg_rating"))).isEqualByComparingTo("5.0");
        assertThat(listingRow()).containsEntry("review_count", 1);
    }

    @Test
    void anOwnerEditAfterAReviewKeepsTheAggregates() throws Exception {
        reviewOk(driver, completed(driver, 10), 5, null);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/owner/listings/" + listingId + "/pricing")
                        .header(HttpHeaders.AUTHORIZATION, ownerAuth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerHour\":35,\"cancellationPolicy\":\"MODERATE\",\"autoApprove\":true,"
                                + "\"amenities\":[\"CCTV\"]}"))
                .andExpect(status().is2xxSuccessful());

        assertThat(((BigDecimal) listingRow().get("avg_rating"))).isEqualByComparingTo("5.0");
        assertThat(listingRow()).containsEntry("review_count", 1);
    }

    @Test
    void anEditThatStartedBeforeAReviewDoesNotOverwriteTheAggregatesItNeverTouched() throws Exception {
        TransactionTemplate edit = new TransactionTemplate(txManager);
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        edit.executeWithoutResult(status -> {
            ParkingListing stale = listings.findById(listingId).orElseThrow(); // sees 0 reviews
            stale.setTitle("Renamed Spot");
            // Meanwhile a review commits in another transaction.
            other.executeWithoutResult(s -> jdbc.update(
                    "update parking_listings set avg_rating = 5.0, review_count = 1 where id = ?", listingId));
        });

        assertThat(jdbc.queryForObject("select title from parking_listings where id = ?", String.class, listingId))
                .isEqualTo("Renamed Spot");
        assertThat(((BigDecimal) listingRow().get("avg_rating"))).isEqualByComparingTo("5.0");
        assertThat(listingRow()).containsEntry("review_count", 1);
    }

    @Test
    void blankCommentIsStoredAsNull() throws Exception {
        long booking = completed(driver, 10);

        review(driver, booking, "{\"rating\":3,\"comment\":\"   \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comment").value(nullValue()));
        assertThat(jdbc.queryForObject("select comment from reviews where booking_id = ?", String.class, booking))
                .isNull();
    }

    @Test
    void twoReviewsAverageToFourAndAHalfAndFillTheDistribution() throws Exception {
        reviewOk(driver, completed(driver, 10), 5, null);
        reviewOk(driver2, completed(driver2, 14), 4, "Good");

        assertThat(((BigDecimal) listingRow().get("avg_rating"))).isEqualByComparingTo("4.5");
        assertThat(listingRow()).containsEntry("review_count", 2);
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.avgRating").value(4.5))
                .andExpect(jsonPath("$.summary.reviewCount").value(2))
                .andExpect(jsonPath("$.summary.distribution.1").value(0))
                .andExpect(jsonPath("$.summary.distribution.2").value(0))
                .andExpect(jsonPath("$.summary.distribution.3").value(0))
                .andExpect(jsonPath("$.summary.distribution.4").value(1))
                .andExpect(jsonPath("$.summary.distribution.5").value(1));
    }

    @Test
    void averageRoundsHalfUpToOneDecimal() throws Exception {
        // 5 + 4 + 4 + 4 = 17 / 4 = 4.25 -> 4.3 (HALF_UP)
        reviewOk(driver, completed(driver, 6), 5, null);
        reviewOk(driver2, completed(driver2, 8), 4, null);
        reviewOk(driver, completed(driver, 10), 4, null);
        reviewOk(driver2, completed(driver2, 12), 4, null);

        assertThat(((BigDecimal) listingRow().get("avg_rating"))).isEqualByComparingTo("4.3");
    }

    @Test
    void bookingThatIsNotCompletedCannotBeReviewed() throws Exception {
        Instant start = tomorrowAt(10);
        long booking = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start,
                start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), booking);

        review(driver, booking, "{\"rating\":5}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REVIEWABLE"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
        assertThat(jdbc.queryForObject("select count(*) from reviews", Integer.class)).isZero();
    }

    @Test
    void anotherDriversBookingIsNotFound() throws Exception {
        long booking = completed(driver, 10);

        review(driver2, booking, "{\"rating\":5}").andExpect(status().isNotFound());
        review(driver2, 999_999, "{\"rating\":5}").andExpect(status().isNotFound());
    }

    @Test
    void reviewWindowIsThirtyDaysAfterTheBookingEnded() throws Exception {
        long old = completed(driver, 10, Duration.ofDays(31));
        long recent = completed(driver, 14, Duration.ofDays(29));

        review(driver, old, "{\"rating\":5}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REVIEWABLE"));
        review(driver, recent, "{\"rating\":5}").andExpect(status().isCreated());
    }

    @Test
    void secondReviewOfTheSameBookingIsRejected() throws Exception {
        long booking = completed(driver, 10);
        reviewOk(driver, booking, 5, null);

        review(driver, booking, "{\"rating\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
        assertThat(listingRow()).containsEntry("review_count", 1);
    }

    @Test
    void concurrentDuplicateSubmissionsCreateExactlyOneReview() throws Exception {
        long booking = completed(driver, 10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return review(driver, booking, "{\"rating\":4}").andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from reviews", Integer.class)).isEqualTo(1);
        assertThat(listingRow()).containsEntry("review_count", 1);
    }

    @Test
    void invalidRatingAndOverlongCommentAreRejected() throws Exception {
        long booking = completed(driver, 10);

        review(driver, booking, "{\"rating\":0}").andExpect(status().isBadRequest());
        review(driver, booking, "{\"rating\":6}").andExpect(status().isBadRequest());
        review(driver, booking, "{}").andExpect(status().isBadRequest());
        review(driver, booking, "{\"rating\":4,\"comment\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest());
        review(driver, booking, "{\"rating\":4,\"comment\":\"" + "x".repeat(1000) + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void lengthLimitsApplyAfterTrimming() throws Exception {
        long booking = completed(driver, 10);

        review(driver, booking, "{\"rating\":4,\"comment\":\"" + "x".repeat(1001) + "   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("comment"));
        review(driver, booking, "{\"rating\":4,\"comment\":\"  " + "x".repeat(1000) + "   \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comment").value("x".repeat(1000)));
    }

    @Test
    void ownersCannotPostReviews() throws Exception {
        long booking = completed(driver, 10);

        mvc.perform(post("/api/v1/bookings/" + booking + "/review").header(HttpHeaders.AUTHORIZATION, ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}"))
                .andExpect(status().isForbidden());
    }

    // ---- reviewable on the booking detail -----------------------------------------------------------------

    @Test
    void reviewableFlipsOncePosted() throws Exception {
        long booking = completed(driver, 10);

        mvc.perform(get("/api/v1/bookings/" + booking).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewable").value(true))
                .andExpect(jsonPath("$.review").value(nullValue()));

        reviewOk(driver, booking, 4, "Fine");

        mvc.perform(get("/api/v1/bookings/" + booking).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(jsonPath("$.reviewable").value(false))
                .andExpect(jsonPath("$.review.rating").value(4))
                .andExpect(jsonPath("$.review.comment").value("Fine"))
                .andExpect(jsonPath("$.review.authorName").value("Ravi K."));
    }

    @Test
    void reviewableIsFalseForOpenBookingsAndOldOnes() throws Exception {
        Instant start = tomorrowAt(10);
        long open = bookingId(reserveOk(mvc, driver.auth(), listingId, driver.vehicleId(), start,
                start.plusSeconds(7200)));
        payOk(mvc, driver.auth(), open);
        long old = completed(driver, 14, Duration.ofDays(31));

        for (long id : new long[] {open, old}) {
            mvc.perform(get("/api/v1/bookings/" + id).header(HttpHeaders.AUTHORIZATION, driver.auth()))
                    .andExpect(jsonPath("$.reviewable").value(false))
                    .andExpect(jsonPath("$.review").value(nullValue()));
        }
    }

    // ---- owner reply --------------------------------------------------------------------------------------

    @Test
    void ownerRepliesOnceToAReviewOfTheirListing() throws Exception {
        long review = reviewOk(driver, completed(driver, 10), 5, "Great");

        reply(ownerAuth, review, "{\"reply\":\"  Thank you!  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(review))
                .andExpect(jsonPath("$.ownerReply").value("Thank you!"))
                .andExpect(jsonPath("$.ownerRepliedAt").isNotEmpty())
                .andExpect(jsonPath("$.rating").value(5));

        reply(ownerAuth, review, "{\"reply\":\"Again\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REPLIED"));
        assertThat(jdbc.queryForObject("select owner_reply from reviews where id = ?", String.class, review))
                .isEqualTo("Thank you!");
    }

    @Test
    void replyRulesAreEnforced() throws Exception {
        long review = reviewOk(driver, completed(driver, 10), 5, "Great");

        reply(otherOwnerAuth, review, "{\"reply\":\"Mine now\"}").andExpect(status().isNotFound());
        reply(ownerAuth, 999_999, "{\"reply\":\"Hello\"}").andExpect(status().isNotFound());
        reply(ownerAuth, review, "{\"reply\":\"   \"}").andExpect(status().isBadRequest());
        reply(ownerAuth, review, "{}").andExpect(status().isBadRequest());
        reply(ownerAuth, review, "{\"reply\":\"" + "y".repeat(501) + "\"}").andExpect(status().isBadRequest());
        reply(driver.auth(), review, "{\"reply\":\"Hello\"}").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select owner_reply from reviews where id = ?", String.class, review)).isNull();

        reply(ownerAuth, review, "{\"reply\":\"" + "y".repeat(501) + "   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reply"));
        reply(ownerAuth, review, "{\"reply\":\"  " + "y".repeat(500) + "   \"}").andExpect(status().isOk());
    }

    @Test
    void ownerListsReviewsOfTheirListingsOnly() throws Exception {
        long first = reviewOk(driver, completed(driver, 10), 5, "Great");
        long second = reviewOk(driver2, completed(driver2, 14), 2, "Meh");
        String firstCode = jdbc.queryForObject("select b.booking_code from bookings b join reviews r "
                + "on r.booking_id = b.id where r.id = ?", String.class, first);

        mvc.perform(get("/api/v1/owner/reviews").header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.content[1].listingId").value(listingId))
                .andExpect(jsonPath("$.content[1].listingTitle").value("Review Spot"))
                .andExpect(jsonPath("$.content[1].bookingCode").value(firstCode))
                .andExpect(jsonPath("$.content[1].authorName").value("Ravi K."));
        mvc.perform(get("/api/v1/owner/reviews?listingId=" + listingId).header(HttpHeaders.AUTHORIZATION, ownerAuth))
                .andExpect(jsonPath("$.content", hasSize(2)));
        mvc.perform(get("/api/v1/owner/reviews").header(HttpHeaders.AUTHORIZATION, otherOwnerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/owner/reviews?listingId=" + listingId).header(HttpHeaders.AUTHORIZATION, otherOwnerAuth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/owner/reviews").header(HttpHeaders.AUTHORIZATION, driver.auth()))
                .andExpect(status().isForbidden());
    }

    // ---- public list --------------------------------------------------------------------------------------

    @Test
    void publicListIsNewestFirstWithAbbreviatedAuthorAndPaging() throws Exception {
        long older = reviewOk(driver, completed(driver, 10), 5, "First");
        long newer = reviewOk(driver2, completed(driver2, 14), 3, "Second");
        reply(ownerAuth, older, "{\"reply\":\"Thanks\"}").andExpect(status().isOk());

        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews.content[*].id", contains((int) newer, (int) older)))
                .andExpect(jsonPath("$.reviews.content[0].authorName").value("Ravi K."))
                .andExpect(jsonPath("$.reviews.content[1].ownerReply").value("Thanks"))
                .andExpect(jsonPath("$.reviews.totalElements").value(2));
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews?page=1&size=1"))
                .andExpect(jsonPath("$.reviews.content", hasSize(1)))
                .andExpect(jsonPath("$.reviews.content[0].id").value(older))
                .andExpect(jsonPath("$.reviews.totalPages").value(2));
    }

    @Test
    void emptyListHasAllFiveDistributionKeys() throws Exception {
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.avgRating").value(0.0))
                .andExpect(jsonPath("$.summary.reviewCount").value(0))
                .andExpect(jsonPath("$.summary.distribution.1").value(0))
                .andExpect(jsonPath("$.summary.distribution.5").value(0))
                .andExpect(jsonPath("$.reviews.content", hasSize(0)));
    }

    @Test
    void publicListIsHiddenUnlessTheListingIsApprovedOrPaused() throws Exception {
        reviewOk(driver, completed(driver, 10), 5, "Great");

        for (ListingStatus hidden : List.of(ListingStatus.DRAFT, ListingStatus.PENDING_REVIEW,
                ListingStatus.REJECTED, ListingStatus.SUSPENDED)) {
            setStatus(hidden);
            mvc.perform(get("/api/v1/listings/" + listingId + "/reviews")).andExpect(status().isNotFound());
        }
        setStatus(ListingStatus.PAUSED);
        mvc.perform(get("/api/v1/listings/" + listingId + "/reviews")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/listings/999999/reviews")).andExpect(status().isNotFound());
    }

    private void setStatus(ListingStatus status) {
        ParkingListing listing = listings.findById(listingId).orElseThrow();
        listing.setStatus(status);
        listings.saveAndFlush(listing);
    }

    // ---- notifications ------------------------------------------------------------------------------------

    @Test
    void ownerIsNotifiedAndEmailedOfANewReview() throws Exception {
        long booking = completed(driver, 10);
        emails.clear();

        reviewOk(driver, booking, 4, "Handy spot");

        Map<String, Object> n = jdbc.queryForMap("select n.title, n.body, n.link from notifications n "
                + "join users u on u.id = n.user_id where u.email = ? and n.type = 'OWNER_NEW_REVIEW'", OWNER_EMAIL);
        assertThat(n).containsEntry("title", "New 4★ review for Review Spot").containsEntry("link", "/owner/reviews");
        assertThat((String) n.get("body")).contains("Handy spot");
        assertThat(emails.sentTo(OWNER_EMAIL)).hasSize(1);
        EmailMessage mail = emails.lastTo(OWNER_EMAIL);
        assertThat(mail.subject()).contains("review").contains("Review Spot");
        assertThat(mail.textBody()).contains("Handy spot").contains("/owner/reviews");
        assertThat(emails.sentTo(DRIVER_EMAIL)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from notifications where type = 'OWNER_NEW_REVIEW'",
                Integer.class)).isEqualTo(1);
    }
}
