package com.smartparking.email;

import lombok.extern.slf4j.Slf4j;

/** Used when SMTP is not configured: prints emails to the console so links can be clicked in development. */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(EmailMessage message) {
        log.info("""

                ===== EMAIL (not sent — SMTP not configured) =====
                To: {}
                Subject: {}

                {}
                ==================================================""",
                message.to(), message.subject(), message.textBody());
    }
}
