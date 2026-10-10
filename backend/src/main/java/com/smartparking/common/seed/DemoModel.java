package com.smartparking.common.seed;

import com.smartparking.booking.BookingActor;
import com.smartparking.booking.BookingStatus;
import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.CancellationPolicy;
import com.smartparking.listing.ListingStatus;
import com.smartparking.listing.ListingType;
import com.smartparking.payment.RefundNotice;
import com.smartparking.pricing.Quote;
import com.smartparking.user.Role;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** The in-memory shapes the demo activity seeder plans with before anything is written to the database. */
final class DemoModel {

    private DemoModel() {
    }

    /** A user. {@code existing} accounts were created by the other seeders and are never modified. */
    static final class Person {
        long id;
        String name;
        String email;
        String phone;
        Role role;
        boolean existing;
        boolean emailVerified = true;
        boolean suspended;
        Instant createdAt;
        /** Chance weight for being picked as the driver of a random booking; 0 keeps the account out of bookings. */
        double weight;
        final List<Veh> vehicles = new ArrayList<>();

        String firstName() {
            return name.split("\\s+")[0];
        }
    }

    static final class Veh {
        long id;
        long userId;
        VehicleType type;
        String plate;
        String makeModel;
        boolean isDefault;
        boolean existing;
        Instant createdAt;
    }

    /** A half-open busy window. */
    record Window(Instant start, Instant end) {
        boolean overlaps(Instant s, Instant e) {
            return start.isBefore(e) && end.isAfter(s);
        }
    }

    static final class Slot {
        long id;
        String label;
        VehicleType type;
        final List<Window> busy = new ArrayList<>();

        boolean isFree(Instant start, Instant end) {
            for (Window w : busy) {
                if (w.overlaps(start, end)) {
                    return false;
                }
            }
            return true;
        }
    }

    record Hours(LocalTime open, LocalTime close) {
    }

    /** A listing, either read from the database (existing demo listings) or created by the seeder. */
    static final class Lst {
        long id;
        Person owner;
        long cityId;
        int tier = 3;
        String title;
        ListingType type;
        boolean open24x7;
        boolean autoApprove;
        CancellationPolicy policy;
        BigDecimal hour;
        BigDecimal day;
        BigDecimal month;
        ListingStatus status = ListingStatus.APPROVED;
        boolean isNew;
        double weight;
        /** Opening hours by ISO day of week (index 1..7); null when closed that day. */
        final Hours[] hours = new Hours[8];
        final List<Slot> slots = new ArrayList<>();
        /** Blocks that close the whole listing. */
        final List<Window> blocks = new ArrayList<>();

        // Only for listings the seeder creates.
        String description;
        String address;
        String pincode;
        double lat;
        double lng;
        List<String> amenities = List.of();
        int photo;
        String rejectionReason;
        int twoWheelers;
        int fourWheelers;
        Instant createdAt;
        Instant submittedAt;
        Instant approvedAt;

        boolean hasSlotFor(VehicleType t) {
            for (Slot s : slots) {
                if (s.type == t) {
                    return true;
                }
            }
            return false;
        }
    }

    record RefundDraft(BigDecimal amount, Instant at, String reason, RefundNotice notice, BookingActor actor) {
    }

    /** A closure of a whole listing (maintenance, a private event). */
    record ListingBlock(Lst listing, Window window, String reason) {
    }

    /** What happened to a booking, which fixes its whole money trail. */
    enum Outcome {
        COMPLETED, ACTIVE, CONFIRMED, AWAITING,
        CANCELLED_DRIVER, CANCELLED_DRIVER_AWAITING, CANCELLED_OWNER, CANCELLED_ADMIN,
        REJECTED_OWNER, REJECTED_SYSTEM, EXPIRED;

        boolean isCancelled() {
            return this == CANCELLED_DRIVER || this == CANCELLED_DRIVER_AWAITING || this == CANCELLED_OWNER
                    || this == CANCELLED_ADMIN;
        }

        boolean isRejected() {
            return this == REJECTED_OWNER || this == REJECTED_SYSTEM;
        }

        BookingStatus status() {
            return switch (this) {
                case COMPLETED -> BookingStatus.COMPLETED;
                case ACTIVE -> BookingStatus.ACTIVE;
                case CONFIRMED -> BookingStatus.CONFIRMED;
                case AWAITING -> BookingStatus.AWAITING_APPROVAL;
                case CANCELLED_DRIVER, CANCELLED_DRIVER_AWAITING, CANCELLED_OWNER, CANCELLED_ADMIN ->
                        BookingStatus.CANCELLED;
                case REJECTED_OWNER, REJECTED_SYSTEM -> BookingStatus.REJECTED;
                case EXPIRED -> BookingStatus.EXPIRED;
            };
        }

        BookingActor canceller() {
            return switch (this) {
                case CANCELLED_DRIVER, CANCELLED_DRIVER_AWAITING -> BookingActor.DRIVER;
                case CANCELLED_OWNER, REJECTED_OWNER -> BookingActor.OWNER;
                case CANCELLED_ADMIN -> BookingActor.ADMIN;
                case REJECTED_SYSTEM -> BookingActor.SYSTEM;
                default -> null;
            };
        }
    }

    /** One planned booking with its timeline; the ledger turns it into rows. */
    static final class Bk {
        long id;
        long paymentId;
        String code;
        Person driver;
        Veh vehicle;
        Lst listing;
        Slot slot;
        Instant start;
        Instant end;
        Quote quote;
        Outcome outcome;

        Instant createdAt;
        /** When the payment was captured (null for EXPIRED). */
        Instant paidAt;
        /** When the owner approved, rejected or the request lapsed; also the cancellation or hold-expiry moment. */
        Instant decidedAt;
        Instant approvedAt;
        Instant holdExpiresAt;
        Instant approvalDeadline;
        Instant confirmedAt;
        Instant completedAt;
        String cancelReason;
        /** The status the booking was cancelled from. */
        BookingStatus cancelledFrom;
        /** What the booking itself refunded when it ended (cancellation, rejection); dispute refunds come on top. */
        BigDecimal refund = BigDecimal.ZERO;

        // Showcase wishes: a review with this rating (-1: never reviewed), a reply to it, an open report.
        int wantedRating;
        boolean forceReply;
        boolean forceDispute;

        // Filled in by the ledger.
        String orderId;
        String providerPaymentId;
        String method;
        boolean disputed;
        Instant startedAt;
        Instant reminderSentAt;
        Instant approvalNudgeSentAt;
        Instant updatedAt;
        /** Every refund of the payment, in time order: the booking's own one first, dispute refunds after. */
        final List<RefundDraft> refunds = new ArrayList<>();

        BookingStatus status() {
            return outcome.status();
        }

        BigDecimal refundTotal() {
            BigDecimal sum = BigDecimal.ZERO;
            for (RefundDraft r : refunds) {
                sum = sum.add(r.amount());
            }
            return sum;
        }

        boolean isPaid() {
            return outcome != Outcome.EXPIRED;
        }
    }
}
