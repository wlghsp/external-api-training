# Phase 1 - Step 2: 멱등 처리

> 같은 요청을 두 번 보내도 중복 결제되지 않게 한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

**선행**: [phase1-step1-pg-simulator.md](phase1-step1-pg-simulator.md)

---

## 결정 지점

- **Idempotency Key는 누가 생성하나**: 클라이언트가 생성해서 헤더(`Idempotency-Key`)로 전달하는 방식이 표준적. 서버가 생성하면 클라이언트가 재시도할 때 같은 키를 재사용할 방법이 없어짐 — 이 근거를 설명할 수 있어야 완료 기준 충족
- **저장 방식**: DB unique constraint / Redis SETNX / Optimistic Lock 중 택1
  - DB unique constraint: 별도 인프라 없이 구현 간단, 트랜잭션 격리 수준에 의존
  - Redis SETNX: 빠른 선점, TTL로 자동 정리 가능하지만 Redis 장애 시 멱등 보장이 깨짐
  - Optimistic Lock: 이미 존재하는 리소스의 상태 전이를 막는 데 적합, "최초 요청 여부" 판단에는 부적합
  - 아래 예시는 **DB unique constraint**로 구현 (결제처럼 영구 기록이 필요한 도메인엔 가장 단순하고 안전)
- **응답 정책**: 중복 요청이면 에러(409)를 줄지, 최초 처리 결과를 그대로 반환할지 — 결제는 클라이언트가 "성공했는지 몰라서" 재시도하는 경우가 많으므로 **최초 결과 재반환**이 일반적

---

## 1. 엔티티 설계

멱등 키와 결제 정보를 한 엔티티로 합침 — 별도 테이블로 분리하면 조인 비용이 생기고, 어차피 멱등 키는 결제 1건과 1:1이므로 분리 이점이 적음.

```java
package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.Getter;

@Entity
@Getter
@Table(name = "payment", uniqueConstraints = {
    @jakarta.persistence.UniqueConstraint(columnNames = "idempotency_key")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentModel extends BaseEntity {

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "transaction_key")
    private String transactionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    public PaymentModel(String idempotencyKey, Long amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "Idempotency Key는 필수입니다.");
        }
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    public void approve(String transactionKey) {
        this.transactionKey = transactionKey;
        this.status = PaymentStatus.APPROVED;
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }
}
```

```java
package com.loopers.domain.payment;

public enum PaymentStatus {
    PENDING, APPROVED, FAILED
}
```

---

## 2. 리포지토리 (도메인 인터페이스 + 구현체)

```java
package com.loopers.domain.payment;

import java.util.Optional;

public interface PaymentRepository {
    PaymentModel save(PaymentModel payment);
    Optional<PaymentModel> findByIdempotencyKey(String idempotencyKey);
}
```

```java
package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<PaymentModel, Long> {
    Optional<PaymentModel> findByIdempotencyKey(String idempotencyKey);
}
```

```java
package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    @Override
    public PaymentModel save(PaymentModel payment) {
        return jpaRepository.save(payment);
    }

    @Override
    public Optional<PaymentModel> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey);
    }
}
```

---

## 3. 멱등 처리 서비스 로직

핵심: unique constraint 위반을 "동시에 같은 키로 두 요청이 들어온 경우"로 간주하고 잡아서, 기존 레코드를 다시 조회해 반환한다.

```java
package com.loopers.domain.payment;

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

    @Transactional
    public PaymentModel getOrCreate(String idempotencyKey, Long amount) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
            .orElseGet(() -> createNew(idempotencyKey, amount));
    }

    private PaymentModel createNew(String idempotencyKey, Long amount) {
        try {
            return paymentRepository.save(new PaymentModel(idempotencyKey, amount));
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 unique constraint 위반 - 이미 다른 스레드가 생성함
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "멱등 처리 중 예기치 못한 오류"));
        }
    }
}
```

`status == APPROVED`인 기존 레코드가 조회되면 그대로 반환하고 PG 재호출을 생략하는 로직은 Facade(step 3)에서 처리.

---

## 확인

- 같은 `idempotencyKey`로 요청을 동시에 2번 보내도 `payment` 테이블에 레코드가 1건만 생기는지 테스트로 검증 (`ExecutorService`로 동시 호출 재현)
- `idempotencyKey` 없이 요청하면 `BAD_REQUEST` 응답 확인

다음 단계 → [phase1-step3-pg-call.md](phase1-step3-pg-call.md)
