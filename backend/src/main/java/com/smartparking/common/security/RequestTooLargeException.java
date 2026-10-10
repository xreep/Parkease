package com.smartparking.common.security;

import java.io.IOException;

/** Thrown while reading a request body that grew past the configured limit. */
public class RequestTooLargeException extends IOException {

    public RequestTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }
}
