package com.smartparking.invoice;

import static com.smartparking.support.PdfTestSupport.textOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingStatus;
import com.smartparking.listing.ParkingListing;
import com.smartparking.payment.Payment;
import com.smartparking.payment.PaymentStatus;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.support.BookingTestSupport;
import com.smartparking.user.User;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReceiptPdfTest {

    private static Invoice invoice(BigDecimal refund) {
        User driver = new User();
        driver.setName("Asha Verma");
        driver.setEmail("asha@example.com");
        User owner = new User();
        owner.setName("Ravi Kumar");
        ParkingListing listing = new ParkingListing();
        listing.setOwner(owner);
        listing.setTitle("FC Road Covered Spot");
        listing.setAddress("12 FC Road, Shivajinagar");
        ParkingSlot slot = new ParkingSlot();
        slot.setLabel("B-07");
        Instant start = Instant.parse("2026-10-09T04:30:00Z"); // 10:00 AM IST
        Booking booking = BookingTestSupport.booking(driver, listing, slot, BookingStatus.CONFIRMED, start,
                start.plusSeconds(7200));
        booking.setBookingCode("PK-ABC234");
        booking.setPlateNumber("MH12AB1234");
        booking.setPricingBreakdown("2 h x Rs.30/h");
        booking.setBaseAmount(new BigDecimal("60.00"));
        booking.setPlatformFee(new BigDecimal("6.00"));
        booking.setGstAmount(new BigDecimal("1.08"));
        booking.setTotalAmount(new BigDecimal("67.08"));
        booking.setRefundAmount(refund);
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setAmount(new BigDecimal("67.08"));
        payment.setPaymentId("pay_Abc123XyZ");
        payment.setMethod("upi");
        payment.setStatus(PaymentStatus.CAPTURED);
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-2026-000042");
        invoice.setBooking(booking);
        invoice.setPayment(payment);
        invoice.setIssuedAt(Instant.parse("2026-10-08T15:30:00Z"));
        return invoice;
    }

    @Test
    void rendersAnA4PdfWithEveryRequiredField() {
        byte[] pdf = ReceiptPdf.render(invoice(BigDecimal.ZERO), new BigDecimal("18"));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text).contains("ParkEase", "Tax invoice / Receipt", "INV-2026-000042", "8 Oct 2026",
                "PK-ABC234", "Asha Verma", "asha@example.com", "FC Road Covered Spot", "12 FC Road, Shivajinagar",
                "B-07", "MH12AB1234", "Fri 9 Oct, 10:00 AM", "12:00 PM", "IST",
                "Parking (2 h x Rs.30/h)", "60.00", "Platform fee", "6.00", "GST on platform fee (18%)", "1.08",
                "Total paid", "67.08", "pay_Abc123XyZ", "upi", "This is a computer-generated receipt.");
        assertThat(text).doesNotContain("Refund");
    }

    @Test
    void showsTheRefundLineOnlyWhenSomethingWasRefunded() {
        String text = textOf(ReceiptPdf.render(invoice(new BigDecimal("67.08")), new BigDecimal("18")));

        assertThat(text).contains("Refunded", "67.08");
    }

    @Test
    void aMissingPaymentMethodDoesNotBreakTheReceipt() {
        Invoice invoice = invoice(BigDecimal.ZERO);
        invoice.getPayment().setMethod(null);

        assertThat(textOf(ReceiptPdf.render(invoice, new BigDecimal("18")))).contains("pay_Abc123XyZ", "Total paid");
    }

    @Test
    void textWithAccentsOrRupeeSignsDoesNotFail() {
        Invoice invoice = invoice(BigDecimal.ZERO);
        invoice.getBooking().getListing().setTitle("Café ₹ Parking – Pune");

        assertThat(ReceiptPdf.render(invoice, new BigDecimal("18"))).isNotEmpty();
    }
}
