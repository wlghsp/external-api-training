package com.pgsimulator.interfaces.api;

public record TransactionResponse(
        String transactionKey,
        String status
) {
}
