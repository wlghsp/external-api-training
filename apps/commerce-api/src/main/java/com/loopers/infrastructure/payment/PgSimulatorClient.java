package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PgApproveResult;
import com.loopers.domain.payment.PgClient;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

@Slf4j
public class PgSimulatorClient implements PgClient {

    private final RestClient restClient;

    public PgSimulatorClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "pg-simulator", fallbackMethod = "approveFallback")
    @Override
    public PgApproveResult approve(Long amount, boolean forceFail) {
        String uri = forceFail ? "/api/v1/transactions?forceFail=true" : "/api/v1/transactions";
        PgTransactionResponse response = restClient.post()
                .uri(uri)
                .body(new PgTransactionRequest(amount))
                .retrieve()
                .body(PgTransactionResponse.class);

        return new PgApproveResult(response.transactionKey(), true);
    }

    // fallbackMethod는 원본과 동일한 파라미터 + Throwable을 마지막 인자로 받아야 함
    private PgApproveResult approveFallback(Long amount, boolean forceFail, Throwable t) {
        log.warn("Circuit Breaker fallback 동작, amount={}, cause={}", amount, t.getMessage());
        throw new CoreException(ErrorType.INTERNAL_ERROR, "결제 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.");
    }
}
