package com.smartparking.payment;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingEvent;
import com.smartparking.booking.BookingEventRepository;
import com.smartparking.booking.BookingMapper;
import com.smartparking.booking.BookingProperties;
import com.smartparking.booking.BookingRepository;
import com.smartparking.booking.BookingStatus;
import com.smartparking.booking.SlotAllocator;
import com.smartparking.booking.dto.BookingDetailDto;
import com.smartparking.booking.dto.CheckoutDto;
import com.smartparking.common.config.AppProperties;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.util.AfterCommit;
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
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
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

/** Payment orders, verification and the idempotent confirmation that turns a paid hold into a booking. */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final String LATE_PAYMENT_NOTE = "Payment received after hold expired";
    static final String SLOT_TAKEN_REASON = "Slot was taken before the payment arrived";

    private final PaymentProvider provider;
    private final PaymentProperties paymentProperties;
    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final BookingEventRepository events;
    private final BookingMapper mapper;
    private final BookingProperties bookingProperties;
    private final OwnerEarningRepository earnings;
    private final InvoiceService invoices;
    private final RefundService refunds;
    private final SlotAllocator allocator;
    private final EmailSender emailSender;
    private final AppProperties app;
    private final EntityManager em;
    private final Clock clock;

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
    @Transactional
    public BookingDetailDto verify(Long driverId, VerifyPaymentRequest request) {
        if (!bookings.existsByIdAndDriverId(request.bookingId(), driverId)) {
            throw ApiException.notFound("Booking not found");
        }
        // Lock first thing: nothing about this payment or booking may be loaded before the lock is held.
        Payment payment = payments.findByOrderIdForUpdate(request.orderId()).orElseThrow(this::verificationFailed);
        if (!payment.getBooking().getId().equals(request.bookingId())
                || !provider.verifyPayment(request.orderId(), request.paymentId(), request.signature())) {
            throw verificationFailed();
        }
        Booking booking = confirmLocked(payment, request.paymentId(), null, BookingActor.DRIVER);
        return mapper.toDetail(booking);
    }

    private ApiException verificationFailed() {
        return ApiException.badRequest("PAYMENT_VERIFICATION_FAILED", "We could not verify this payment");
    }

    // ---- confirmation ---------------------------------------------------------------------------------------

    /**
     * Records a captured payment and moves the booking forward. Idempotent: the payment row is locked, and a payment
     * that is already captured changes nothing. Used by the checkout verification and the provider webhook.
     */
    @Transactional
    public Booking confirmPayment(String orderId, String paymentId, String method, BookingActor actor) {
        Payment payment = payments.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        return confirmLocked(payment, paymentId, method, actor);
    }

    private Booking confirmLocked(Payment payment, String paymentId, String method, BookingActor actor) {
        Booking booking = (Booking) Hibernate.unproxy(payment.getBooking());
        if (payment.getPaymentId() != null && payment.getStatus() != PaymentStatus.CREATED
                && payment.getStatus() != PaymentStatus.FAILED) {
            if (!payment.getPaymentId().equals(paymentId)) {
                log.warn("Order {} is already paid by {}; ignoring payment {}", payment.getOrderId(),
                        payment.getPaymentId(), paymentId);
            }
            return booking;
        }

        Instant now = clock.instant();
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
            case PENDING_PAYMENT -> booking.acceptPayment(listing.isAutoApprove(), now, approvalWindow);
            case EXPIRED -> {
                if (!allocator.revive(booking.getId(), listing.isAutoApprove(), now, approvalWindow)) {
                    cancelLatePayment(booking, payment, actor);
                    return booking;
                }
                em.refresh(booking); // pick up what the separate transaction committed
                note = LATE_PAYMENT_NOTE;
            }
            default -> {
                log.warn("Payment {} captured for booking {} in status {}; leaving the booking unchanged",
                        paymentId, booking.getBookingCode(), from);
                return booking;
            }
        }

        invoices.issue(booking, payment);
        saveEarning(booking, listing);
        addEvent(booking, from, booking.getStatus(), actor, note);
        queueEmails(booking, listing);
        return booking;
    }

    /** The hold lapsed and someone else took the slot: cancel and give the money back. */
    private void cancelLatePayment(Booking booking, Payment payment, BookingActor actor) {
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledBy(BookingActor.SYSTEM);
        booking.setCancelReason(SLOT_TAKEN_REASON);
        addEvent(booking, BookingStatus.EXPIRED, BookingStatus.CANCELLED, BookingActor.SYSTEM, SLOT_TAKEN_REASON);
        Refund refund = refunds.refundFull(payment, SLOT_TAKEN_REASON);
        log.warn("Late payment for booking {} (via {}): slot taken, refund {} is {}", booking.getBookingCode(), actor,
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

    private void addEvent(Booking booking, BookingStatus from, BookingStatus to, BookingActor actor, String note) {
        BookingEvent event = new BookingEvent();
        event.setBooking(booking);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setActor(actor);
        event.setNote(note);
        events.save(event);
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
