package com.loopers.domain.payment;

public record PaymentInfo(String idempotencyKey, Long amount, PaymentStatus status) {
}
