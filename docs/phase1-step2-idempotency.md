# Phase 1 - Step 2: 멱등 처리

> 같은 요청을 두 번 보내도 중복 결제되지 않게 한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.
> 왜 이렇게 설계했는지 서사 중심 설명은 [phase1-step2-explainer.md](phase1-step2-explainer.md) 참고.

**선행**: [phase1-step1-pg-simulator.md](phase1-step1-pg-simulator.md)

---

## 결정 지점

| 항목 | 선택 | 근거 |
|---|---|---|
| Idempotency Key 생성 주체 | 클라이언트 (`Idempotency-Key` 헤더) | 서버가 생성하면 클라이언트가 재시도할 때 같은 키를 재사용할 방법이 없음 |
| 저장 방식 | Redis SETNX(1차) + DB unique constraint(최종 안전망) | 아래 표 참고 |
| 응답 정책 | 중복 요청이면 최초 처리 결과를 그대로 반환 | 클라이언트는 "성공했는지 몰라서" 재시도하는 경우가 많음 |

**저장 방식을 이중화한 이유:**

| 방식만 단독 사용 시 | 문제 |
|---|---|
| DB unique constraint만 | 매 요청 INSERT 시도 → 실패(예외)로 중복 감지 → 트래픽 몰릴 때 쓰기/예외 비용 누적 |
| Redis SETNX만 | 선점은 빠르지만 Redis 장애 시 멱등 보장이 통째로 사라짐 |
| Optimistic Lock | 기존 리소스의 상태 전이를 막는 도구라, "최초 요청 여부" 판단엔 부적합 (채택 안 함) |

→ Redis로 대부분의 중복을 빠르게 걸러내고, DB가 최종 심판 역할을 하는 이중 구조. Redis가 선점은 성공했는데 DB 저장에 실패하는 엣지 케이스는 아래 4번에서 처리.

---

## 1. 도메인 타입 정의

### 1-1. PgApproveResult

PG 호출 결과 — "실제로 어떻게 부르는지"(인프라, step 3)와 "성공하면 뭘 받는지"(도메인)를 분리하기 위해 여기서 먼저 정의.

```java
package com.loopers.domain.payment;

public record PgApproveResult(String transactionKey, boolean approved) {
}
```

### 1-2. PaymentStatus — sealed interface (Java 21)

| 방식 | 문제/해결 |
|---|---|
| `enum PENDING/APPROVED/FAILED` (기존) | "APPROVED면 transactionKey가 반드시 있다"는 규칙이 코드로 강제되지 않음 |
| `sealed interface` + `record` (채택) | `Approved(transactionKey)`처럼 상태별 데이터를 타입에 내장 → 컴파일 타임 제약 |

```java
package com.loopers.domain.payment;

public sealed interface PaymentStatus {
    record Pending() implements PaymentStatus {}
    record Approved(String transactionKey) implements PaymentStatus {}
    record Failed(String reason) implements PaymentStatus {}
}
```

`switch` 패턴 매칭 시 세 하위 타입 중 하나라도 안 다루면 컴파일 에러 — enum + `default:`처럼 조용히 넘어가지 않는다.

### 1-3. PaymentInfo

API 응답으로 내보낼 값만 담은 불변 객체. 영속성 컨텍스트에 묶인 `PaymentModel` 자체는 도메인 밖으로 노출하지 않는다.

```java
package com.loopers.domain.payment;

public record PaymentInfo(String idempotencyKey, Long amount, PaymentStatus status) {
}
```

---

## 2. PaymentModel

JPA는 sealed interface를 컬럼에 직접 매핑 못 함 → **영속용(`PaymentStatusType` enum)**과 **도메인용(`PaymentStatus` sealed interface)**을 분리하고, `status()`에서 변환을 캡슐화.

```java
package com.loopers.domain.payment;

enum PaymentStatusType {
    PENDING, APPROVED, FAILED
}
```

```java
package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.function.Supplier;

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
        this.statusType = PaymentStatusType.PENDING;
    }

    // 영속 컬럼 → sealed interface 재구성. 조합 규칙은 여기 안에만 존재.
    public PaymentStatus status() {
        return switch (statusType) {
            case PENDING -> new PaymentStatus.Pending();
            case APPROVED -> new PaymentStatus.Approved(transactionKey);
            case FAILED -> new PaymentStatus.Failed(failureReason);
        };
    }

    // Tell, Don't Ask: 상태를 묻지 않고 "승인해라"만 지시받는다.
    public PaymentModel approveOrElse(Supplier<PgApproveResult> pgApprover) {
        return switch (status()) {
            case PaymentStatus.Approved approved -> this; // 이미 승인됨 - 재호출 없이 반환
            case PaymentStatus.Pending pending -> approve(pgApprover.get());
            case PaymentStatus.Failed failed ->
                throw new CoreException(ErrorType.CONFLICT, "이미 실패 처리된 결제입니다: " + failed.reason());
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
```

---

## 3. 리포지토리

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

## 4. IdempotencyLock (Redis 1차 선점)

`modules:redis`가 `RedisTemplate<String, String>` 빈을 이미 제공 — 별도 설정 불필요.

`setIfAbsent(key, value, ttl)` = Redis `SET key value NX EX ttl`, "확인+설정"이 원자적으로 한 번에 처리된다 (GET 후 SET처럼 나누면 그 사이 경쟁 상태가 생김).

```java
package com.loopers.infrastructure.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class IdempotencyLock {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5); // 결제 처리 최대 소요시간 기준
    private static final String KEY_PREFIX = "payment:idempotency:";

    private final RedisTemplate<String, String> redisTemplate;

    // true = 최초 선점 성공, false = 이미 선점됨
    public boolean acquire(String idempotencyKey) {
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(KEY_PREFIX + idempotencyKey, "1", LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(String idempotencyKey) {
        redisTemplate.delete(KEY_PREFIX + idempotencyKey);
    }
}
```

**명시적 `release()`를 안 쓰는 이유**: 결제 성공 후엔 같은 키가 다시 올 필요가 없어 TTL 자동 만료로 충분. "재시도를 허용하고 싶은 실패"는 `fail()` 후 `release()` 경로가 필요한데, 이건 Phase 2(재시도 가능/불가능 분류)와 맞닿는 확장 포인트로 남겨둔다.

---

## 5. PaymentService (Redis 1차 + DB 최종 확정)

| 케이스 | 처리 |
|---|---|
| Redis 선점 성공 | DB에 새로 저장 |
| Redis 선점 실패 (이미 선점됨) | DB에서 기존 레코드 조회 후 반환 |
| Redis 선점 성공했는데 DB `unique constraint` 위반 | Redis 장애/TTL 만료 등으로 1차 방어가 뚫린 경우 — DB가 최종 심판, 기존 레코드 재조회 |

```java
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
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "결제가 처리 중입니다. 잠시 후 다시 시도해주세요."));
        }
        return createNew(idempotencyKey, amount);
    }

    private PaymentModel createNew(String idempotencyKey, Long amount) {
        try {
            return paymentRepository.save(new PaymentModel(idempotencyKey, amount));
        } catch (DataIntegrityViolationException e) {
            // Redis 1차 방어가 뚫린 엣지 케이스 - DB unique constraint가 최종 안전망
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "멱등 처리 중 예기치 못한 오류"));
        }
    }
}
```

PG 재호출 여부 판단은 `PaymentModel.approveOrElse(...)` 내부에서 처리 — 여기(`PaymentService`)는 관여하지 않는다.

---

## 확인

| # | 확인 항목 | 방법 | 기대 결과 |
|---|---|---|---|
| 1 | 동시 요청 멱등성 | 같은 `idempotencyKey`로 `ExecutorService` 등을 이용해 동시 2회 요청 | `payment` 테이블에 레코드 1건만 생성 |
| 2 | Idempotency Key 누락 | 헤더 없이 요청 | `BAD_REQUEST` 응답 |
| 3 | Redis 장애 시 동작 | Redis 컨테이너를 잠깐 내리고 요청 | `setIfAbsent`가 예외를 던지는지 직접 확인 — 지금 코드엔 이 경로 처리가 없다 (의도적으로 비워둔 지점, 직접 채워볼 것) |
| 4 | TTL 트레이드오프 이해 | (코드 실행 아님) 자문자답 | "Redis 선점 성공 후 DB 저장 전 프로세스가 죽으면, TTL 5분 동안 같은 키로 재시도가 막힌다"를 설명할 수 있는지 |

다음 단계 → [phase1-step3-pg-call.md](phase1-step3-pg-call.md)
