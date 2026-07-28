package com.loopers.domain.payment;

public record PgApproveResult(String transactionKey, boolean approved) {
}
