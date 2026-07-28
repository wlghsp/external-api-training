package com.loopers.domain.payment;

public sealed interface PaymentStatus {

    record Pending() implements PaymentStatus {}

    record Approved(String transactionKey) implements PaymentStatus {}

    record Failed(String reason) implements PaymentStatus {}
}
