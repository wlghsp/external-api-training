package com.loopers.interfaces.api.payment;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Payment V1 API", description = "결제 API 입니다.")
public interface PaymentV1ApiSpec {

    @Operation(
            summary = "결제 요청",
            description = "Idempotency-Key 헤더로 멱등성을 보장하며 결제를 요청합니다."
    )
    ApiResponse<PaymentV1Dto.ChargeResponse> charge(
            String idempotencyKey,
            PaymentV1Dto.ChargeRequest request,
            boolean forceFail
    );
}
