package com.loopers.domain.payment;

public interface PgClient {
    PgApproveResult approve(Long amount, boolean forceFail);
}
