package com.smartparking.email;

public record EmailMessage(String to, String subject, String textBody, String htmlBody) {
}
