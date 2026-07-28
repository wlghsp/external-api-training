package com.loopers.domain.payment;

import com.loopers.infrastructure.payment.IdempotencyLock;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final IdempotencyLock idempotencyLock;

    @Transactional
    public PaymentModel getOrCreate(String idempotencyKey, Long amount) {
        boolean acquired = idempotencyLock.acquire(idempotencyKey);
        if (!acquired) {
            // Redis 기준 이미 처리 중/처리됨 - DB에서 실제 상태를 확인
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "결제가 처리 중입니다. 잠시 후 다시 시도해주세요."));
        }
        return createNew(idempotencyKey, amount);
    }

    private PaymentModel createNew(String idempotencyKey, Long amount) {
        try {
            return paymentRepository.save(new PaymentModel(idempotencyKey, amount));
        } catch (DataIntegrityViolationException e) {
            // Redis 선점은 성공했는데 DB에 이미 존재 - Redis 장애/TTL 만료 등으로 1차 방어가 뚫린 경우.
            // DB unique constraint가 최종 안전망으로 동작해 여기서 잡힌다.
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "멱등 처리 중 예기치 못한 오류"));
        }
    }
}
