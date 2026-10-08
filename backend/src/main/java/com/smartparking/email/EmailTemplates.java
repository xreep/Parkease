package com.smartparking.email;

import com.smartparking.booking.Booking;
import com.smartparking.common.util.Ist;
import com.smartparking.user.User;
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
                "You have a new confirmed booking.\n\n" + details(booking), "View bookings", link);
    }

    public static EmailMessage bookingApprovalNeeded(User owner, Booking booking, String link) {
        return build(owner, "Approve a booking request – ParkEase",
                "A driver has paid for a booking and is waiting for your approval. Please respond by "
                        + formatTime(booking.getApprovalDeadline()) + ", otherwise the request is declined "
                        + "automatically and the driver is refunded.\n\n" + details(booking),
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

    private static String details(Booking b) {
        return "Booking " + b.getBookingCode() + "\n"
                + b.getListing().getTitle() + ", slot " + b.getSlot().getLabel() + "\n"
                + formatTime(b.getStartTime()) + " to " + formatTime(b.getEndTime()) + " (IST)\n"
                + "Total: ₹" + b.getTotalAmount().toPlainString();
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
