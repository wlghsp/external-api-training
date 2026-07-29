package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentInfo;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PgClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final PgClient pgClient;

    @Transactional
    public PaymentInfo charge(String idempotencyKey, Long amount, boolean forceFail) {
        PaymentModel payment = paymentService.getOrCreate(idempotencyKey, amount);
        payment.approveOrElse(() -> pgClient.approve(amount, forceFail));
        return payment.toInfo();
    }

}
