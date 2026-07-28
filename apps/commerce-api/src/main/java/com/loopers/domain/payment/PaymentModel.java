package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.function.Supplier;

import static com.loopers.domain.payment.PaymentStatusType.*;

@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(columnNames = "idempotency_key")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentModel extends BaseEntity {

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "transaction_key")
    private String transactionKey;

    @Column(name = "failure_reason")
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_type", nullable = false)
    private PaymentStatusType statusType;

    public PaymentModel(String idempotencyKey, Long amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "Idempotency Key는 필수입니다.");
        }
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.statusType = PENDING;
    }

    /**
     * 영속 컬럼 (statusType + nullable 필드들)을 sealed interface로 재구성
     * - 엔티티 밖에서는 이 조합 규칙을 몰라도 된다
     */
    public PaymentStatus status() {
        return switch (statusType) {
            case PENDING -> new PaymentStatus.Pending();
            case APPROVED -> new PaymentStatus.Approved(transactionKey);
            case FAILED -> new PaymentStatus.Failed(failureReason);
        };
    }

    /**
     * 이미 승인됐으면 PG를 다시 부르지 않고 그대로 반환, 아니면 pgApprover로 승인을 시도한다.
     * "승인됐는가?"를 밖으로 묻지 않고, "승인해라"는 지시만 받는다.
     */
    public PaymentModel approveOrElse(Supplier<PgApproveResult> pgApprover) {
        return switch (status()) {
            case PaymentStatus.Approved approved -> this; // 이미 승인됨 - 재호출 없이 그대로 반환
            case PaymentStatus.Pending pending -> approve(pgApprover.get());
            case PaymentStatus.Failed failed -> throw new CoreException(ErrorType.CONFLICT, "이미 실패 처리된 결제입니다:" + failed.reason());
        };
    }

    private PaymentModel approve(PgApproveResult result) {
        this.transactionKey = result.transactionKey();
        this.statusType = PaymentStatusType.APPROVED;
        return this;
    }

    public void fail(String reason) {
        this.failureReason = reason;
        this.statusType = PaymentStatusType.FAILED;
    }

    public PaymentInfo toInfo() {
        return new PaymentInfo(idempotencyKey, amount, status());
    }

}
