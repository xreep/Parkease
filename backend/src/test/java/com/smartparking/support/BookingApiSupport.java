package com.smartparking.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP-level helpers for booking flow tests (drivers, vehicles, reserve, mock payment). */
public final class BookingApiSupport {

    public record Driver(String auth, Long vehicleId) {
    }

    private BookingApiSupport() {
    }

    /** Tomorrow at 00:00 UTC plus {@code hours}; always on a quarter hour and in the future. */
    public static Instant tomorrowAt(int hours) {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).plus(24 + hours, ChronoUnit.HOURS);
    }

    /** Registers a DRIVER with one FOUR_WHEELER vehicle. */
    public static Driver driverWithVehicle(MockMvc mvc, String email) throws Exception {
        String auth = AuthTestSupport.bearer(AuthTestSupport.accessToken(AuthTestSupport.register(mvc, email, "DRIVER")));
        String body = mvc.perform(post("/api/v1/me/vehicles").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FOUR_WHEELER\",\"plateNumber\":\"MH12AB1234\",\"makeModel\":\"Honda City\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Driver(auth, ((Number) JsonPath.read(body, "$.id")).longValue());
    }

    public static ResultActions reserve(MockMvc mvc, String auth, Long listingId, Long vehicleId, Instant start,
                                        Instant end) throws Exception {
        return mvc.perform(post("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"listingId\":%d,\"vehicleId\":%d,\"start\":\"%s\",\"end\":\"%s\"}"
                        .formatted(listingId, vehicleId, start, end)));
    }

    /** Reserves and expects 201; returns the CheckoutDto JSON. */
    public static String reserveOk(MockMvc mvc, String auth, Long listingId, Long vehicleId, Instant start,
                                   Instant end) throws Exception {
        return reserve(mvc, auth, listingId, vehicleId, start, end)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    public static long bookingId(String checkoutJson) {
        return ((Number) JsonPath.read(checkoutJson, "$.booking.id")).longValue();
    }

    public static String orderId(String checkoutJson) {
        return JsonPath.read(checkoutJson, "$.payment.orderId");
    }

    /** Calls the mock pay endpoint; returns its JSON ({orderId, paymentId, signature}). */
    public static String mockPay(MockMvc mvc, String auth, long bookingId) throws Exception {
        return mvc.perform(post("/api/v1/payments/mock/pay").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bookingId\":" + bookingId + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    public static ResultActions verify(MockMvc mvc, String auth, long bookingId, String orderId, String paymentId,
                                       String signature) throws Exception {
        return mvc.perform(post("/api/v1/payments/verify").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":%d,\"orderId\":\"%s\",\"paymentId\":\"%s\",\"signature\":\"%s\"}"
                        .formatted(bookingId, orderId, paymentId, signature)));
    }

    /** Mock-pays and verifies a booking (expects 200); returns the BookingDetailDto JSON. */
    public static String payOk(MockMvc mvc, String auth, long bookingId) throws Exception {
        String pay = mockPay(mvc, auth, bookingId);
        return verify(mvc, auth, bookingId, JsonPath.read(pay, "$.orderId"), JsonPath.read(pay, "$.paymentId"),
                JsonPath.read(pay, "$.signature"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
