package com.smartparking.email;

public interface EmailSender {

    /** Sends the message. Implementations log failures instead of throwing. */
    void send(EmailMessage message);
}
