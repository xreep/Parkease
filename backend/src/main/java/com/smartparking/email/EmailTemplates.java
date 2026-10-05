package com.smartparking.email;

import com.smartparking.user.User;
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
                """.formatted(HtmlUtils.htmlEscape(firstName), HtmlUtils.htmlEscape(body),
                HtmlUtils.htmlEscape(link), cta, HtmlUtils.htmlEscape(link));
        return new EmailMessage(user.getEmail(), subject, text, html);
    }
}
