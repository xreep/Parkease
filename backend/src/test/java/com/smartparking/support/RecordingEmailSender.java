package com.smartparking.support;

import com.smartparking.email.EmailMessage;
import com.smartparking.email.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outgoing emails in memory so tests can read links from them. */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public List<EmailMessage> sentTo(String to) {
        return sent.stream().filter(m -> m.to().equals(to)).toList();
    }

    public EmailMessage lastTo(String to) {
        List<EmailMessage> messages = sentTo(to);
        if (messages.isEmpty()) {
            throw new AssertionError("No email sent to " + to);
        }
        return messages.getLast();
    }

    public void clear() {
        sent.clear();
    }

    public static String tokenFrom(EmailMessage message) {
        Matcher matcher = TOKEN.matcher(message.textBody());
        if (!matcher.find()) {
            throw new AssertionError("No token link in email: " + message.subject());
        }
        return matcher.group(1);
    }
}
