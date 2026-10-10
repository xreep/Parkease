package com.smartparking.email;

import com.smartparking.common.seed.DemoMode;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the real sender on a demo deployment. The generated demo people live at {@code @example.com} and the showcase
 * logins at {@code @parkease.dev}: nothing there is a mailbox, so mail to them would only bounce and hurt the sender's
 * reputation. It is dropped (logged at INFO); real visitors' addresses are mailed as usual.
 */
public class DemoMailFilter implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(DemoMailFilter.class);

    private final EmailSender delegate;

    public DemoMailFilter(EmailSender delegate) {
        this.delegate = delegate;
    }

    @Override
    public void send(EmailMessage message) {
        String to = message.to() == null ? "" : message.to().trim().toLowerCase(Locale.ROOT);
        if (to.endsWith("@example.com") || to.endsWith(DemoMode.SHOWCASE_DOMAIN)) {
            log.info("Demo mode: not sending \"{}\" to {} (placeholder demo address)", message.subject(), message.to());
            return;
        }
        delegate.send(message);
    }
}
