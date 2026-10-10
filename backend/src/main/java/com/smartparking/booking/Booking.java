package com.smartparking.booking;

import com.smartparking.common.model.VehicleType;
import com.smartparking.common.persistence.BaseEntity;
import com.smartparking.listing.ParkingListing;
import com.smartparking.pricing.PricingMode;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.user.User;
import com.smartparking.vehicle.Vehicle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bookings")
public class Booking extends BaseEntity {

    @Column(nullable = false, updatable = false)
    private String bookingCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id")
    private User driver;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id")
    private ParkingListing listing;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id")
    private ParkingSlot slot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleType vehicleType;

    @Column(nullable = false)
    private String plateNumber;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PricingMode pricingMode;

    @Column(nullable = false)
    private String pricingBreakdown;

    @Column(nullable = false)
    private BigDecimal baseAmount;

    @Column(nullable = false)
    private BigDecimal platformFee;

    @Column(nullable = false)
    private BigDecimal gstAmount;

    /** The GST rate (percent) the amounts were priced with; later changes of the platform setting do not touch it. */
    @Column(nullable = false)
    private BigDecimal gstPercent = new BigDecimal("18.00");

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    private Instant holdExpiresAt;

    private Instant approvalDeadline;

    private Instant confirmedAt;

    private Instant completedAt;

    /** When the "your parking starts soon" reminder went out; null until then (it is sent at most once). */
    private Instant reminderSentAt;

    /** When the owner was nudged about the approaching approval deadline; null until then (sent at most once). */
    private Instant approvalNudgeSentAt;

    @Enumerated(EnumType.STRING)
    private BookingActor cancelledBy;

    private String cancelReason;

    /**
     * Applies a received payment: auto-approve listings confirm immediately, others wait for the owner (until
     * {@code min(now + approvalWindow, startTime)}: a request is never left open past the moment parking would start).
     * The unpaid hold no longer applies.
     */
    public void acceptPayment(boolean autoApprove, Instant now, Duration approvalWindow) {
        holdExpiresAt = null;
        if (autoApprove) {
            status = BookingStatus.CONFIRMED;
            confirmedAt = now;
            approvalDeadline = null;
        } else {
            status = BookingStatus.AWAITING_APPROVAL;
            Instant deadline = now.plus(approvalWindow);
            approvalDeadline = deadline.isAfter(startTime) ? startTime : deadline;
        }
    }

    /** True when this booking currently blocks its slot: paid/approved states, or an unexpired unpaid hold. */
    public boolean isLiveAt(Instant now) {
        return switch (status) {
            case AWAITING_APPROVAL, CONFIRMED, ACTIVE -> true;
            case PENDING_PAYMENT -> holdExpiresAt != null && holdExpiresAt.isAfter(now);
            default -> false;
        };
    }
}
