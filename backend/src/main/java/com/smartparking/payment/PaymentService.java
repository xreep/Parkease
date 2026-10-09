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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int MAX_FAILURE_REASON = 300;
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
    private final AtomicInteger deadlockRetries = new AtomicInteger();

    /** How often a confirmation was repeated because Postgres picked it as a deadlock victim (should stay 0). */
    public int deadlockRetries() {
        return deadlockRetries.get();
    }

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
     * Mock provider only (and only while {@code app.payments.mock-enabled}): mints the ids and signature a real checkout would hand back, so the frontend's mock dialog
     * can call {@code /payments/verify} exactly like Razorpay's handler. Not found when a real provider is active.
     */
    @Transactional(readOnly = true)
    public MockPayResponse mockPay(Long driverId, Long bookingId) {
        if (!paymentProperties.mockEnabled() || !(provider instanceof MockPaymentProvider mock)) {
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
        Long bookingId = confirmWithProvider(request.orderId(), request.paymentId(), null, BookingActor.DRIVER);
        return tx.execute(s -> mapper.toDetail(bookings.findById(bookingId).orElseThrow()));
    }

    /**
     * What the payment row says the provider must have charged. {@code paidByAnother}: the order is already paid by a
     * different provider payment than the one being confirmed.
     */
    private record Expected(Long bookingId, BigDecimal amount, String currency, boolean alreadyConfirmed,
                            boolean paidByAnother) {
    }

    /**
     * Confirms a payment the caller was told about (checkout callback, webhook) only after asking the provider for
     * it: the payment must belong to this order, be for exactly the order amount in its currency, and be captured
     * (an authorized one is captured first). Anything else is {@code PAYMENT_VERIFICATION_FAILED}. A payment that is
     * already confirmed is not asked about again. The provider calls are network calls, so this must run outside
     * transactions. {@code fallbackMethod} is used only if the provider does not report the payment method.
     */
    public Long confirmWithProvider(String orderId, String paymentId, String fallbackMethod, BookingActor actor) {
        Expected expected = expectedFor(orderId, paymentId);
        if (expected.alreadyConfirmed()) {
            return confirmPayment(orderId, paymentId, fallbackMethod, actor);
        }
        return confirmFetched(orderId, expected, provider.fetchPayment(paymentId), fallbackMethod, actor);
    }

    private Expected expectedFor(String orderId, String paymentId) {
        return tx.execute(s -> {
            Payment payment = payments.findByOrderId(orderId).orElseThrow(() -> ApiException.notFound("Payment not found"));
            boolean settled = payment.getPaymentId() != null && payment.getStatus() != PaymentStatus.CREATED
                    && payment.getStatus() != PaymentStatus.FAILED;
            return new Expected(payment.getBooking().getId(), payment.getAmount(), payment.getCurrency(),
                    settled && paymentId.equals(payment.getPaymentId()),
                    settled && !paymentId.equals(payment.getPaymentId()));
        });
    }

    private Long confirmFetched(String orderId, Expected expected, ProviderPayment fetched, String fallbackMethod,
                                BookingActor actor) {
        // Only the offline mock may leave the order, amount or currency unreported; a real provider that does is wrong.
        boolean lenient = provider.type() == PaymentProviderType.MOCK;
        long expectedPaise = RefundService.toPaise(expected.amount());
        boolean sameOrder = fetched.orderId() == null ? lenient : orderId.equals(fetched.orderId());
        boolean sameAmount = fetched.amountPaise() == null ? lenient : fetched.amountPaise() == expectedPaise;
        boolean sameCurrency = fetched.currency() == null ? lenient : expected.currency().equals(fetched.currency());
        if (!sameOrder || !sameAmount || !sameCurrency) {
            log.error("Payment {} does not match order {} (provider says order {}, {} {}; expected {} {} paise)",
                    fetched.paymentId(), orderId, fetched.orderId(), fetched.amountPaise(), fetched.currency(),
                    expectedPaise, expected.currency());
            throw verificationFailed();
        }
        if (ProviderPayment.AUTHORIZED.equals(fetched.status())) {
            if (expected.paidByAnother()) {
                // The order is already paid, so this is the customer's second attempt. Capturing it would only mean
                // taking money to give it back; an authorization that is never captured is voided by the provider.
                log.error("Order {} is already paid, but payment {} was authorized as well; not capturing it",
                        orderId, fetched.paymentId());
                return expected.bookingId();
            }
            fetched = capture(orderId, expected, fetched);
        } else if (!ProviderPayment.CAPTURED.equals(fetched.status())) {
            log.warn("Payment {} of order {} is '{}' at the provider, not captured", fetched.paymentId(), orderId,
                    fetched.status());
            if (fetched.errorDescription() != null) {
                recordFailureReason(orderId, fetched.errorDescription());
            }
            throw verificationFailed();
        }
        String method = fetched.method() != null ? fetched.method() : fallbackMethod;
        return confirmPayment(orderId, fetched.paymentId(), method, actor);
    }

    /**
     * Captures an authorized payment. If that fails, the payment is looked at once more: a concurrent request (the
     * webhook, or a second verify) may have captured it first, which is as good as success.
     */
    private ProviderPayment capture(String orderId, Expected expected, ProviderPayment authorized) {
        try {
            provider.capture(authorized.paymentId(), RefundService.toPaise(expected.amount()), expected.currency());
            return authorized;
        } catch (ApiException e) {
            ProviderPayment again = provider.fetchPayment(authorized.paymentId());
            if (ProviderPayment.CAPTURED.equals(again.status())) {
                log.info("Capture of payment {} failed but it is captured now; carrying on", authorized.paymentId());
                return again;
            }
            recordFailureReason(orderId, PaymentProviderException.describe(e));
            throw e;
        }
    }

    /** Notes why the provider did not (yet) accept this order's payment; the payment stays open for another try. */
    private void recordFailureReason(String orderId, String reason) {
        tx.executeWithoutResult(s -> payments.findByOrderIdForUpdate(orderId).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.CREATED || p.getStatus() == PaymentStatus.FAILED) {
                p.setFailureReason(reason.length() > MAX_FAILURE_REASON ? reason.substring(0, MAX_FAILURE_REASON) : reason);
            }
        }));
    }

    /**
     * Asks the provider what happened to an order whose confirmation never reached us and confirms every captured
     * payment it has (an extra captured payment on an already-paid order is refunded). Returns how many captured
     * payments were applied. A network call plus the usual confirmation transactions: call it outside a transaction.
     */
    public int reconcileOrder(String orderId) {
        int applied = 0;
        for (ProviderPayment found : provider.fetchOrderPayments(orderId)) {
            if (!ProviderPayment.CAPTURED.equals(found.status())) {
                continue;
            }
            try {
                confirmFetched(orderId, expectedFor(orderId, found.paymentId()), found, null, BookingActor.SYSTEM);
                applied++;
            } catch (ApiException e) {
                log.error("Order {}: provider payment {} could not be applied: {}", orderId, found.paymentId(),
                        e.getMessage());
            }
        }
        return applied;
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
        boolean slotTaken = false;
        boolean retriedDeadlock = false;
        while (true) {
            boolean refundBecauseSlotTaken = slotTaken;
            try {
                return tx.execute(s -> confirmAttempt(orderId, paymentId, method, actor, refundBecauseSlotTaken));
            } catch (RuntimeException e) {
                String state = SqlStates.of(e);
                if (SqlStates.EXCLUSION_VIOLATION.equals(state) && !slotTaken) {
                    log.warn("Slot of the booking for order {} was taken while its payment arrived; refunding", orderId);
                    slotTaken = true;
                } else if (SqlStates.DEADLOCK_DETECTED.equals(state) && !retriedDeadlock) {
                    // Every path takes payment row then booking row, so this should not happen; the stale-hold sweep
                    // below can still lock other bookings' rows. Postgres aborted this attempt as the victim, which
                    // rolled it back completely, so repeating it once is safe. A second deadlock propagates.
                    log.warn("Deadlock while confirming order {}; retrying once", orderId);
                    retriedDeadlock = true;
                    deadlockRetries.incrementAndGet();
                } else {
                    throw e;
                }
            }
        }
    }

    private Long confirmAttempt(String orderId, String paymentId, String method, BookingActor actor,
                                boolean slotTaken) {
        Long bookingId = payments.findBookingIdByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        Instant now = clock.instant();
        // Lock order everywhere is payment row, then booking row. The payment goes first, before the sweep below
        // (which row-locks the lapsed bookings it expires, possibly this very one).
        payments.findByOrderIdForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Payment not found"));
        // Frees the slot from lapsed holds (possibly this booking's own). Clears the persistence context, so the
        // payment and booking are (re)loaded after it, never before.
        bookings.expireStaleHolds(List.of(bookings.findSlotIdById(bookingId)), now);

        Payment payment = payments.findByOrderIdForUpdate(orderId) // already ours; re-read fresh after the clear
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        Booking booking = bookings.findByIdForUpdate(bookingId).orElseThrow();
        if (payment.getPaymentId() != null && payment.getStatus() != PaymentStatus.CREATED
                && payment.getStatus() != PaymentStatus.FAILED) {
            if (!payment.getPaymentId().equals(paymentId)) {
                // The customer paid the same order twice: the booking stays as it is and the extra money goes back.
                log.error("Order {} is already paid by {}, but payment {} was captured as well; refunding the extra payment",
                        orderId, payment.getPaymentId(), paymentId);
                refunds.refundExtraPayment(booking, payment, paymentId);
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
                refundUnpayable(booking, payment, from);
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
        Refund refund = refunds.refundAndNote(booking, payment, BookingActor.SYSTEM, reason, RefundNotice.SLOT_LOST);
        log.warn("Late payment for booking {} (via {}): {}; refund {} is {}", booking.getBookingCode(), actor, reason,
                refund.getProviderRefundId(), refund.getStatus());
    }

    /**
     * A capture arrived for a booking that cannot be paid any more (cancelled, rejected, completed, ...): the money
     * is recorded as captured and handed straight back, and the booking is left exactly as it was.
     */
    private void refundUnpayable(Booking booking, Payment payment, BookingStatus status) {
        String reason = "Booking no longer payable (" + status + ")";
        log.warn("Payment {} captured for booking {} in status {}; refunding it in full",
                payment.getPaymentId(), booking.getBookingCode(), status);
        events.record(booking, status, status, BookingActor.SYSTEM,
                "Payment received but the booking is no longer payable (" + status + ")");
        refunds.refundAndNote(booking, payment, BookingActor.SYSTEM, reason, RefundNotice.BOOKING_CLOSED);
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
