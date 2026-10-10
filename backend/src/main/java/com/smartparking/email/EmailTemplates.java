package com.smartparking.email;

import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingStatus;
import com.smartparking.payment.RefundNotice;
import com.smartparking.common.util.Ist;
import com.smartparking.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.web.util.HtmlUtils;

/** Plain-text + simple HTML account emails. Richer Thymeleaf templates arrive with booking emails in Phase 5. */
public final class EmailTemplates {

    private EmailTemplates() {
    }

    public static EmailMessage verifyEmail(User user, String link) {
        return build(user, "Verify your email – ParkEase",
                "Confirm your email address to finish setting up your ParkEase account. This link expires in 24 hours.",
                "Verify email", link);
    }

    public static EmailMessage resetPassword(User user, String link) {
        return build(user, "Reset your password – ParkEase",
                "We received a request to reset your ParkEase password. This link expires in 30 minutes. "
                        + "If you did not ask for this, you can ignore this email.",
                "Reset password", link);
    }

    public static EmailMessage ownerVerified(User user, String link) {
        return build(user, "You're verified – ParkEase",
                "Your identity has been verified. You can now submit parking listings for approval.",
                "List your parking", link);
    }

    public static EmailMessage ownerRejected(User user, String reason, String link) {
        return build(user, "Verification needs attention – ParkEase",
                "We could not verify your document. Reason: " + reason + ". "
                        + "Please upload a clearer or different document.",
                "Upload again", link);
    }

    public static EmailMessage listingApproved(User user, String title, String link) {
        return build(user, "Your listing is live – ParkEase",
                "Good news! \"" + title + "\" has been approved and drivers can now find it.",
                "View your listings", link);
    }

    public static EmailMessage listingRejected(User user, String title, String reason, String link) {
        return build(user, "Listing needs changes – ParkEase",
                "\"" + title + "\" was not approved. Reason: " + reason + ". Update it and submit again.",
                "Edit listing", link);
    }

    public static EmailMessage accountSuspended(User user, String reason, String link) {
        return build(user, "Your account has been suspended – ParkEase",
                "Your ParkEase account has been suspended. Reason: " + reason + ". "
                        + "Existing bookings are still honoured. If you think this is a mistake, please contact support.",
                "Visit ParkEase", link);
    }

    public static EmailMessage listingSuspended(User user, String title, String reason, String link) {
        return build(user, "Listing suspended – ParkEase",
                "\"" + title + "\" has been suspended and is no longer visible to drivers. Reason: " + reason + ". "
                        + "Bookings that already exist are still honoured.",
                "View your listings", link);
    }

    public static EmailMessage listingReinstated(User user, String title, String link) {
        return build(user, "Listing reinstated – ParkEase",
                "\"" + title + "\" has been reinstated and drivers can find it again.",
                "View your listings", link);
    }

    public static EmailMessage disputeOpened(User owner, Booking booking, String category, String link) {
        return build(owner, "A driver opened a dispute – ParkEase",
                "A driver reported a problem (" + category + ") with booking " + booking.getBookingCode() + " at "
                        + booking.getListing().getTitle() + ". You can add your side of the story once.",
                "View report", link);
    }

    public static EmailMessage disputeResponse(User driver, Booking booking, String link) {
        return build(driver, "The owner responded to your report – ParkEase",
                "The owner has responded to the problem you reported for booking " + booking.getBookingCode() + ".",
                "View report", link);
    }

    public static EmailMessage disputeResolved(User user, Booking booking, String outcome, String link) {
        return build(user, "Dispute resolved – ParkEase",
                "The report for booking " + booking.getBookingCode() + " has been resolved. " + outcome,
                "View booking", link);
    }

    public static EmailMessage bookingCancelledByAdmin(User driver, Booking booking, String reason, String refundLine,
                                                       String link) {
        return build(driver, "Booking cancelled – ParkEase",
                "Your booking was cancelled by ParkEase. Reason: " + reason + "\n\n" + details(booking) + "\n\n"
                        + refundLine, "View booking", link);
    }

    public static EmailMessage bookingCancelledByAdminForOwner(User owner, Booking booking, String reason,
                                                               String link) {
        return build(owner, "Booking cancelled by ParkEase – ParkEase",
                "A booking for your parking was cancelled by ParkEase. Reason: " + reason + "\n\n"
                        + ownerDetails(booking), "View bookings", link);
    }

    public static EmailMessage payoutSent(User owner, String amount, int count, String reference, String link) {
        return build(owner, "Payout sent – ParkEase",
                "We have sent you ₹" + amount + " for " + count + (count == 1 ? " booking" : " bookings")
                        + ".\nPayout reference: " + reference,
                "View earnings", link);
    }

    /** Booking emails read lazy associations of {@code booking}; build them inside a transaction. */
    public static EmailMessage bookingConfirmed(User driver, Booking booking, String link) {
        return build(driver, "Booking confirmed – ParkEase",
                "Your parking is confirmed.\n\n" + details(booking), "View booking", link);
    }

    public static EmailMessage bookingRequested(User driver, Booking booking, String link) {
        return build(driver, "Booking request sent – ParkEase",
                "Your payment was received and your request has been sent to the owner, who has until "
                        + formatTime(booking.getApprovalDeadline()) + " to respond. "
                        + "If they decline or do not respond, you get a full refund.\n\n" + details(booking),
                "View booking", link);
    }

    public static EmailMessage newBookingForOwner(User owner, Booking booking, String link) {
        return build(owner, "New booking – ParkEase",
                "You have a new confirmed booking.\n\n" + ownerDetails(booking), "View bookings", link);
    }

    public static EmailMessage bookingApprovalNeeded(User owner, Booking booking, String link) {
        return build(owner, "Approve a booking request – ParkEase",
                "A driver has paid for a booking and is waiting for your approval. Please respond by "
                        + formatTime(booking.getApprovalDeadline()) + ", otherwise the request is declined "
                        + "automatically and the driver is refunded.\n\n" + ownerDetails(booking),
                "Review request", link);
    }

    public static EmailMessage bookingApproved(User driver, Booking booking, String link) {
        return build(driver, "Your booking is confirmed – ParkEase",
                "The owner approved your request, so your parking is confirmed.\n\n" + details(booking),
                "View booking", link);
    }

    public static EmailMessage bookingRejected(User driver, Booking booking, String reason, String link) {
        return build(driver, "Booking request declined – ParkEase",
                "The owner could not accept your booking request. Reason: " + reason + "\n\n"
                        + "A full refund of ₹" + booking.getTotalAmount().toPlainString() + " is on its way.\n\n"
                        + details(booking),
                "View booking", link);
    }

    public static EmailMessage bookingAutoRejected(User driver, Booking booking, String link) {
        return build(driver, "Booking request expired – ParkEase",
                "The owner did not respond to your request in time, so it was declined automatically.\n\n"
                        + "A full refund of ₹" + booking.getTotalAmount().toPlainString() + " is on its way.\n\n"
                        + details(booking),
                "View booking", link);
    }

    /** The driver cancelled; {@code refundLine} says what happens to the money (see the cancellation service). */
    public static EmailMessage bookingCancelled(User driver, Booking booking, String refundLine, String link) {
        return build(driver, "Booking cancelled – ParkEase",
                "Your booking was cancelled.\n\n" + refundLine + "\n\n" + details(booking), "View booking", link);
    }

    /** Tells the owner that a driver cancelled a paid booking; the slot is free again. */
    public static EmailMessage bookingCancelledByDriver(User owner, Booking booking, String reason, String link) {
        return build(owner, "Booking cancelled by driver – ParkEase",
                "The driver cancelled this booking, so the slot is free again."
                        + (reason == null ? "" : "\nReason: " + reason) + "\n\n" + ownerDetails(booking),
                "View bookings", link);
    }

    public static EmailMessage bookingCancelledByOwner(User driver, Booking booking, String reason,
                                                       String refundLine, String link) {
        return build(driver, "Your booking was cancelled by the owner – ParkEase",
                "The owner had to cancel your booking. Reason: " + reason + "\n\n" + refundLine + "\n\n"
                        + details(booking),
                "View booking", link);
    }

    /**
     * Sent when a refund the driver was not told about yet has gone through. {@code pending}: the refund is accepted
     * but still settling. For {@link RefundNotice#BOOKING_CLOSED} the wording follows the booking's status; for
     * {@link RefundNotice#CANCELLATION} it names the {@code amount} refunded (which may be only part of the total).
     */
    public static EmailMessage paymentRefunded(User driver, Booking booking, String link, RefundNotice notice,
                                               boolean pending, BigDecimal amount) {
        if (notice == RefundNotice.CANCELLATION) {
            return build(driver, pending ? "Refund initiated – ParkEase" : "Refund issued – ParkEase",
                    "Your refund of ₹" + amount.toPlainString() + " for the cancelled booking "
                            + booking.getBookingCode() + (pending
                            ? " is being processed to your original payment method."
                            : " has been issued to your original payment method.") + "\n\n" + details(booking),
                    "View booking", link);
        }
        String money = "your payment of ₹" + booking.getTotalAmount().toPlainString()
                + (pending ? " is being refunded in full." : " has been refunded in full.");
        String lead = notice == RefundNotice.BOOKING_CLOSED
                ? "This booking was already " + closedWord(booking.getStatus()) + ", so "
                : "We couldn't hold your slot, so ";
        return build(driver, "Payment refunded – ParkEase", lead + money + "\n\n" + details(booking),
                "View booking", link);
    }

    /** Reminder to the driver shortly before parking starts. */
    public static EmailMessage startingSoon(User driver, Booking booking, String link) {
        return build(driver, "Your parking starts soon – ParkEase",
                "Your parking starts at " + formatTime(booking.getStartTime()) + " (IST).\n\n"
                        + "Address: " + booking.getListing().getAddress() + "\n" + bookingLines(booking),
                "View booking", link);
    }

    /** Nudge to the owner when a booking request is about to lapse unanswered. */
    public static EmailMessage approvalReminder(User owner, Booking booking, String link) {
        return build(owner, "Respond to a booking request – ParkEase",
                "A booking request is still waiting for your answer. Please respond by "
                        + formatTime(booking.getApprovalDeadline()) + " (IST), otherwise it is declined "
                        + "automatically and the driver is refunded.\n\n" + ownerDetails(booking),
                "Review request", link);
    }

    /** Tells the owner a driver reviewed their listing. */
    public static EmailMessage ownerNewReview(User owner, String listingTitle, int rating, String comment,
                                              String link) {
        String body = "A driver left a " + rating + "★ review for " + listingTitle + ".";
        if (comment != null && !comment.isBlank()) {
            body += "\n\n\"" + comment + "\"";
        }
        body += "\n\nYou can reply to it publicly once.";
        return build(owner, "New " + rating + "★ review for " + listingTitle + " – ParkEase", body,
                "View reviews", link);
    }

    private static String closedWord(BookingStatus status) {
        return switch (status) {
            case CANCELLED -> "cancelled";
            case REJECTED -> "declined";
            case COMPLETED -> "completed";
            default -> status.name().toLowerCase().replace('_', ' ');
        };
    }

    private static String details(Booking b) {
        return bookingLines(b) + "\nTotal: ₹" + b.getTotalAmount().toPlainString();
    }

    /** Owners see what they earn, not what the driver pays (which includes the platform fee and GST). */
    private static String ownerDetails(Booking b) {
        return bookingLines(b) + "\nYou earn ₹" + b.getBaseAmount().toPlainString();
    }

    private static String bookingLines(Booking b) {
        return "Booking " + b.getBookingCode() + "\n"
                + b.getListing().getTitle() + ", slot " + b.getSlot().getLabel() + "\n"
                + formatTime(b.getStartTime()) + " to " + formatTime(b.getEndTime()) + " (IST)";
    }

    private static String formatTime(Instant time) {
        return Ist.format(time);
    }

    private static EmailMessage build(User user, String subject, String body, String cta, String link) {
        String firstName = user.getName().split("\\s+")[0];
        String text = "Hi " + firstName + ",\n\n" + body + "\n\n" + cta + ": " + link + "\n\n— Team ParkEase";
        String html = """
                <div style="font-family:Arial,sans-serif;max-width:520px;margin:auto;color:#0f172a">
                  <h2 style="color:#047857">ParkEase</h2>
                  <p>Hi %s,</p>
                  <p>%s</p>
                  <p><a href="%s" style="display:inline-block;background:#059669;color:#fff;padding:12px 20px;border-radius:8px;text-decoration:none;font-weight:bold">%s</a></p>
                  <p style="font-size:12px;color:#64748b">Or paste this link into your browser:<br>%s</p>
                </div>
                """.formatted(HtmlUtils.htmlEscape(firstName), HtmlUtils.htmlEscape(body).replace("\n", "<br>"),
                HtmlUtils.htmlEscape(link), cta, HtmlUtils.htmlEscape(link));
        return new EmailMessage(user.getEmail(), subject, text, html);
    }
}
