package com.smartparking.payment;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingEvents;
import com.smartparking.booking.BookingMapper;
import com.smartparking.booking.BookingProperties;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.booking.dto.CheckoutDto;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.util.AfterCommit;
import com.smartparking.common.util.SqlStates;
import com.smartparking.earning.EarningStatus;
import com.smartparking.earning.OwnerEarning;
import com.smartparking.earning.OwnerEarningRepository;
import com.smartparking.email.EmailMessage;
import com.smartparking.email.EmailSender;
import com.smartparking.email.EmailTemplates;
import com.smartparking.invoice.InvoiceService;
import com.smartparking.listing.ParkingListing;
import com.smartparking.payment.dto.MockPayResponse;
import com.smartparking.payment.dto.VerifyPaymentRequest;
import com.smartparking.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Payment orders, verification and the idempotent confirmation that turns a paid hold into a booking. */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final String LATE_PAYMENT_NOTE = "Payment received after hold expired";
    static final String SLOT_TAKEN_REASON = "Slot was taken before the payment arrived";
    static final String START_PASSED_REASON = "The booking start time passed before the payment arrived";

    private final PaymentProvider provider;
    private final PaymentProperties paymentProperties;
    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final BookingEvents events;
    private final BookingMapper mapper;
    private final BookingProperties bookingProperties;
    private final OwnerEarningRepository earnings;
    private final InvoiceService invoices;
    private final RefundService refunds;
    private final EmailSender emailSender;
    private final AppProperties app;
    private final Clock clock;
    private final TransactionTemplate tx;

    // ---- checkout -------------------------------------------------------------------------------------------

    /** Creates the provider order for a booking. A network call: keep it outside database transactions. */
    public ProviderOrder createProviderOrder(String bookingCode, BigDecimal total) {
        return provider.createOrder(bookingCode, RefundService.toPaise(total), Map.of("bookingCode", bookingCode));
    }

    /** Records the provider order as the booking's payment (status CREATED). */
    @Transactional
    public Payment recordOrder(Booking booking, ProviderOrder order) {
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProvider(provider.type());
        payment.setOrderId(order.orderId());
        payment.setAmount(booking.getTotalAmount());
        payment.setCurrency(order.currency());
        payment.setStatus(PaymentStatus.CREATED);
        return payments.save(payment);
    }

    /** Checkout details for the payment dialog. Call within a transaction (reads the lazy driver and listing). */
    public CheckoutDto.PaymentInfo checkoutInfo(Booking booking, Payment payment) {
        User driver = booking.getDriver();
        String keyId = payment.getProvider() == PaymentProviderType.RAZORPAY
                ? paymentProperties.razorpayOrEmpty().keyId() : null;
        return new CheckoutDto.PaymentInfo(payment.getProvider(), payment.getOrderId(),
                RefundService.toPaise(payment.getAmount()), payment.getCurrency(), keyId, "ParkEase",
                booking.getListing().getTitle(),
                new CheckoutDto.Prefill(driver.getName(), driver.getEmail(), driver.getPhone()));
    }

    /**
     * Mock provider only: mints the ids and signature a real checkout would hand back, so the frontend's mock dialog
     * can call {@code /payments/verify} exactly like Razorpay's handler. Not found when a real provider is active.
     */
    @Transactional(readOnly = true)
    public MockPayResponse mockPay(Long driverId, Long bookingId) {
        if (!(provider instanceof MockPaymentProvider mock)) {
            throw ApiException.notFound("Not found");
        }
        if (!bookings.existsByIdAndDriverId(bookingId, driverId)) {
            throw ApiException.notFound("Booking not found");
        }
        Payment payment = payments.findByBookingId(bookingId).orElseThrow(() -> ApiException.notFound("Payment not found"));
        String paymentId = "pay_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new MockPayResponse(payment.getOrderId(), paymentId, mock.signForTesting(payment.getOrderId(), paymentId));
    }

    // ---- verification ---------------------------------------------------------------------------------------

    /** Checks the checkout signature for the driver's own booking and confirms the payment (idempotent). */
    public BookingDetailDto verify(Long driverId, VerifyPaymentRequest request) {
        if (!bookings.existsByIdAndDriverId(request.bookingId(), driverId)) {
            throw ApiException.notFound("Booking not found");
        }
        Long orderBookingId = payments.findBookingIdByOrderId(request.orderId()).orElseThrow(this::verificationFailed);
        if (!orderBookingId.equals(request.bookingId())
                || !provider.verifyPayment(request.orderId(), request.paymentId(), request.signature())) {
            throw verificationFailed();
        }
        Long bookingId = confirmPayment(request.orderId(), request.paymentId(), null, BookingActor.DRIVER);
        return tx.execute(s -> mapper.toDetail(bookings.findById(bookingId).orElseThrow()));
    }

    private ApiException verificationFailed() {
        return ApiException.badRequest("PAYMENT_VERIFICATION_FAILED", "We could not verify this payment");
    }

    // ---- confirmation ---------------------------------------------------------------------------------------

    /**
     * Records a captured payment and moves the booking forward; returns the booking id. Idempotent: the payment and
     * booking rows are locked, and a payment that is already captured changes nothing. Used by the checkout
     * verification and the provider webhook. Opens its own transactions, so call it outside one.
     *
     * <p>Locking the booking row means the stale-hold sweep (a bulk update) cannot expire it between our status check
     * and our write, and any competing reservation can only insert after we commit. If a lapsed booking turns out
     * to have lost its slot anyway, the exclusion constraint rejects the revive (SQLState 23P01); that attempt is
     * rolled back as a whole and repeated as a cancel-and-refund, so captured money is never left unaccounted for.
     */
    public Long confirmPayment(String orderId, String paymentId, String method, BookingActor actor) {
        try {
            return tx.execute(s -> confirmAttempt(orderId, paymentId, method, actor, false));
        } catch (RuntimeException e) {
            if (!SqlStates.EXCLUSION_VIOLATION.equals(SqlStates.of(e))) {
                throw e;
            }
            log.warn("Slot of the booking for order {} was taken while its payment arrived; refunding", orderId);
            return tx.execute(s -> confirmAttempt(orderId, paymentId, method, actor, true));
        }
    }

    private Long confirmAttempt(String orderId, String paymentId, String method, BookingActor actor,
                                boolean slotTaken) {
        Long bookingId = payments.findBookingIdByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        Instant now = clock.instant();
        // Frees the slot from lapsed holds (possibly this booking's own). Clears the persistence context, so it
        // must run before any entity is loaded in this transaction.
        bookings.expireStaleHolds(List.of(bookings.findSlotIdById(bookingId)), now);

        Payment payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow();
        if (payment.getPaymentId() != null && payment.getStatus() != PaymentStatus.CREATED
                && payment.getStatus() != PaymentStatus.FAILED) {
            if (!payment.getPaymentId().equals(paymentId)) {
                log.warn("Order {} is already paid by {}; ignoring payment {}", orderId, payment.getPaymentId(), paymentId);
            }
            return bookingId;
        }

        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setPaymentId(paymentId);
        payment.setMethod(method);
        payment.setFailureReason(null);
        payment.setCapturedAt(now);

        BookingStatus from = booking.getStatus();
        ParkingListing listing = booking.getListing();
        Duration approvalWindow = Duration.ofHours(bookingProperties.approvalHours());
        String note = "Payment received";
        switch (from) {
            case PENDING_PAYMENT, EXPIRED -> {
                String refundReason = slotTaken ? SLOT_TAKEN_REASON
                        : from == BookingStatus.EXPIRED && !booking.getStartTime().isAfter(now) ? START_PASSED_REASON
                        : null;
                if (refundReason != null) {
                    cancelAndRefund(booking, payment, from, refundReason, actor);
                    return bookingId;
                }
                booking.acceptPayment(listing.isAutoApprove(), now, approvalWindow);
                if (from == BookingStatus.EXPIRED) {
                    bookings.flush(); // re-takes the slot now: a lost race surfaces here as SQLState 23P01
                    note = LATE_PAYMENT_NOTE;
                }
            }
            default -> {
                log.warn("Payment {} captured for booking {} in status {}; leaving the booking unchanged",
                        paymentId, booking.getBookingCode(), from);
                return bookingId;
            }
        }

        invoices.issue(booking, payment);
        saveEarning(booking, listing);
        events.record(booking, from, booking.getStatus(), actor, note);
        queueEmails(booking, listing);
        return bookingId;
    }

    /** The hold lapsed and the slot is gone (or the start has passed): cancel and give the money back. */
    private void cancelAndRefund(Booking booking, Payment payment, BookingStatus from, String reason,
                                 BookingActor actor) {
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledBy(BookingActor.SYSTEM);
        booking.setCancelReason(reason);
        events.record(booking, from, BookingStatus.CANCELLED, BookingActor.SYSTEM, reason);
        Refund refund = refunds.refundFull(payment, reason);
        log.warn("Late payment for booking {} (via {}): {}; refund {} is {}", booking.getBookingCode(), actor, reason,
                refund.getProviderRefundId(), refund.getStatus());
    }

    private void saveEarning(Booking booking, ParkingListing listing) {
        OwnerEarning earning = new OwnerEarning();
        earning.setBooking(booking);
        earning.setOwner(listing.getOwner());
        earning.setGross(booking.getBaseAmount());
        earning.setCommission(booking.getPlatformFee());
        earning.setNet(booking.getBaseAmount());
        earning.setStatus(EarningStatus.HELD);
        earnings.save(earning);
    }

    /** Builds the emails now (lazy data) and sends them once the transaction has committed. */
    private void queueEmails(Booking booking, ParkingListing listing) {
        String driverLink = app.frontendUrl() + "/driver/bookings/" + booking.getId();
        String ownerLink = app.frontendUrl() + "/owner/bookings";
        User driver = booking.getDriver();
        User owner = listing.getOwner();
        List<EmailMessage> messages = new ArrayList<>();
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            messages.add(EmailTemplates.bookingConfirmed(driver, booking, driverLink));
            messages.add(EmailTemplates.newBookingForOwner(owner, booking, ownerLink));
        } else {
            messages.add(EmailTemplates.bookingRequested(driver, booking, driverLink));
            messages.add(EmailTemplates.bookingApprovalNeeded(owner, booking, ownerLink));
        }
        AfterCommit.run(() -> messages.forEach(emailSender::send));
    }
}
