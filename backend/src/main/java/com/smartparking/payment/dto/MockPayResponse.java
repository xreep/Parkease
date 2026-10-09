package com.smartparking.payment.dto;

public record MockPayResponse(String orderId, String paymentId, String signature) {
}
