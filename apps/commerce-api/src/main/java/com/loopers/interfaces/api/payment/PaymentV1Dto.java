package com.loopers.interfaces.api.payment;

import com.loopers.domain.payment.PaymentInfo;
import com.loopers.domain.payment.PaymentStatus;

public class PaymentV1Dto {
    public record ChargeRequest(Long amount) {}

    public record ChargeResponse(String idempotencyKey, Long amount, PaymentStatus status) {
        public static ChargeResponse from(PaymentInfo info) {
            return new ChargeResponse(info.idempotencyKey(), info.amount(), info.status());
        }
    }
}
