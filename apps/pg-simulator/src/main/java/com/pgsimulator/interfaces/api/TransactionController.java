package com.pgsimulator.interfaces.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;


@Slf4j
@RestController
public class TransactionController {

    @PostMapping("/api/v1/transactions")
    public ResponseEntity<?> charge(
            @RequestBody TransactionRequest request,
            @RequestParam(required = false) Long delayMs,
            @RequestParam(required = false) Boolean forceFail,
            @RequestParam(required = false, defaultValue = "0") double failureRate

    ) {
        if (delayMs != null  && delayMs > 0) {
            sleep(delayMs);
        }

        boolean shouldFail = Boolean.TRUE.equals(forceFail) || ThreadLocalRandom.current().nextDouble() < failureRate;

        if (shouldFail) {
            log.warn("PG 시뮬레이터 - 강제 실패 응답");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse("PG 응답 실패 (시뮬레이션)"));
        }
        String transactionKey = UUID.randomUUID().toString();
        log.info("PG 시뮬레이터 - 결제 승인, transactionKey: {}", transactionKey);
        return ResponseEntity.ok(new TransactionResponse(transactionKey, "APPROVED"));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
