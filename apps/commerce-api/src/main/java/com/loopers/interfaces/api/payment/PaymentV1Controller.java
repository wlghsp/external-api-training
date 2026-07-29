package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentFacade;
import com.loopers.domain.payment.PaymentInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller implements PaymentV1ApiSpec {
    private final PaymentFacade paymentFacade;

    @PostMapping
    @Override
    public ApiResponse<PaymentV1Dto.ChargeResponse> charge(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentV1Dto.ChargeRequest request
    ) {
        PaymentInfo info = paymentFacade.charge(idempotencyKey, request.amount());
        return ApiResponse.success(PaymentV1Dto.ChargeResponse.from(info));
    }
}
