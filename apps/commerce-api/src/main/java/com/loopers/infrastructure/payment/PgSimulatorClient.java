package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PgApproveResult;
import com.loopers.domain.payment.PgClient;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

@Slf4j
public class PgSimulatorClient implements PgClient {

    private final RestClient restClient;

    public PgSimulatorClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public PgApproveResult approve(Long amount) {
        try {
            PgTransactionResponse response = restClient.post()
                    .uri("/api/v1/transactions")
                    .body(new PgTransactionRequest(amount))
                    .retrieve()
                    .body(PgTransactionResponse.class);

            return new PgApproveResult(response.transactionKey(), true);
        } catch (Exception e) {
            log.warn("PG 승인 요청 실패: {}", e.getMessage());
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 승인에 실패했습니다.");
        }
    }
}
