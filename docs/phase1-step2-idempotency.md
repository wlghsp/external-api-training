# Phase 1 - Step 2: 멱등 처리

> 같은 요청을 두 번 보내도 중복 결제되지 않게 한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

**선행**: [phase1-step1-pg-simulator.md](phase1-step1-pg-simulator.md)

---

## 결정 지점

- **Idempotency Key는 누가 생성하나**: 클라이언트가 생성해서 헤더(`Idempotency-Key`)로 전달하는 방식이 표준적. 서버가 생성하면 클라이언트가 재시도할 때 같은 키를 재사용할 방법이 없어짐 — 이 근거를 설명할 수 있어야 완료 기준 충족
- **저장 방식**: DB unique constraint 단독이 아니라 **Redis SETNX(1차) + DB unique constraint(최종 안전망)** 이중 구조로 간다
  - DB unique constraint만 쓰면: 매 요청마다 INSERT를 시도하고 실패(예외)로 중복을 감지하는 구조라, 트래픽이 몰릴 때 불필요한 쓰기 시도와 예외 비용이 쌓임
  - Redis SETNX만 쓰면: 선점은 빠르지만 Redis 장애 시 멱등 보장이 통째로 사라짐 — 결제처럼 돈이 걸린 도메인에서 이 리스크는 단독으로 감수하기 어려움
  - 그래서 **Redis로 빠르게 1차 선점(대부분의 중복 요청을 여기서 걸러냄) → DB unique constraint로 최종 확정**하는 이중 구조를 쓴다. 실무에서 고트래픽 결제/주문 API가 흔히 쓰는 패턴
  - Optimistic Lock은 별도로 검토했으나, 이미 존재하는 리소스의 상태 전이를 막는 데 적합한 도구라 "최초 요청 여부" 판단에는 맞지 않아 채택하지 않음
- **이중 구조가 만드는 새 문제**: Redis 선점엔 성공했는데 DB 저장에 실패하면(서버 크래시, DB 장애 등) Redis에만 흔적이 남고 DB에는 없는 상태가 된다 — 이걸 어떻게 정리할지가 이 구조의 핵심 학습 포인트 (아래 3번 참고)
- **응답 정책**: 중복 요청이면 에러(409)를 줄지, 최초 처리 결과를 그대로 반환할지 — 결제는 클라이언트가 "성공했는지 몰라서" 재시도하는 경우가 많으므로 **최초 결과 재반환**이 일반적

---

## 1. 엔티티 설계

멱등 키와 결제 정보를 한 엔티티로 합침 — 별도 테이블로 분리하면 조인 비용이 생기고, 어차피 멱등 키는 결제 1건과 1:1이므로 분리 이점이 적음.

**Getter/Setter로 상태를 꺼내서 바깥에서 판단하는 절차지향적 방식(Anemic Domain Model) 대신, "이미 처리됐으면 재사용하고 아니면 승인시켜라"라는 판단 자체를 엔티티 안에 둔다 (Tell, Don't Ask).** 외부(Facade)는 `PaymentModel`에게 무엇을 하라고 지시할 뿐, 상태를 조회해서 분기하지 않는다.

승인 시 PG 호출이 필요한데, 엔티티가 `PgClient`(인프라 인터페이스)를 직접 의존하면 도메인이 인프라 세부사항에 묶이므로(DIP 위반), PG 호출 결과를 만들어주는 콜백(`Supplier<PgApproveResult>`)만 받는다. 실제 PG 클라이언트가 무엇인지는 엔티티가 알 필요가 없다.

`PgApproveResult`는 "PG 호출이 성공하면 무엇을 돌려주는가"를 나타내는 도메인 타입이라 여기서 먼저 정의한다 — PG를 실제로 어떻게 부르는지(`PgClient` 구현체, HTTP 통신 등)는 step 3에서 다룰 인프라 관심사이고, 이 결과 타입 자체는 도메인이 알아야 한다.

```java
package com.loopers.domain.payment;

public record PgApproveResult(String transactionKey, boolean approved) {
}
```

### 1-1. PaymentStatus를 sealed interface로 (Java 21)

`enum PENDING/APPROVED/FAILED` 대신 **sealed interface + record**로 설계한다. enum은 세 상태가 각각 어떤 데이터를 들고 다녀야 하는지 타입으로 강제하지 못한다 — `transactionKey`가 "APPROVED일 때만 값이 있고 나머지는 null"이라는 규칙이 지금까지는 주석과 사람의 기억에만 의존했다. sealed interface로 바꾸면 **"Approved는 반드시 transactionKey를 가진다"는 게 컴파일 타임 제약**이 된다 — `Approved` record 자체에 `transactionKey` 필드가 있고, `Pending`/`Failed`엔 아예 그 필드가 존재하지 않는다.

```java
package com.loopers.domain.payment;

public sealed interface PaymentStatus {

    record Pending() implements PaymentStatus {}

    record Approved(String transactionKey) implements PaymentStatus {}

    record Failed(String reason) implements PaymentStatus {}
}
```

`switch` 패턴 매칭(Java 21 정식)으로 상태별 분기 시 컴파일러가 **모든 하위 타입을 다뤘는지 전수 검사**한다 — 나중에 상태를 하나 더 추가했는데 분기 처리를 빠뜨리면 컴파일 에러로 바로 드러난다 (enum의 `default:` 분기처럼 조용히 넘어가지 않는다).

### 1-2. JPA와의 접점 — sealed interface는 컬럼에 직접 매핑할 수 없다

JPA는 `@Enumerated`처럼 enum은 컬럼으로 다루지만, sealed interface/record를 직접 컬럼에 매핑하는 표준 방법이 없다. 그래서 **영속용 표현(테이블에 실제로 저장되는 형태)과 도메인 로직용 표현(sealed interface)을 분리**한다 — 엔티티는 내부적으로 `PaymentStatusType`(순수 enum, DB 컬럼 전용) + nullable 컬럼들을 갖고, `status()` 조회 시 이걸 조합해 sealed interface 인스턴스로 재구성한다. 이 변환은 엔티티 내부에 캡슐화되어 있어서 외부는 이 사실을 몰라도 된다.

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
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.util.function.Supplier;

@Entity
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

    /** 영속 컬럼(statusType + nullable 필드들)을 sealed interface로 재구성 — 엔티티 밖에서는 이 조합 규칙을 몰라도 된다 */
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

`switch (status())`가 `PaymentStatus`의 세 하위 타입 중 하나라도 빠뜨리면 컴파일 에러가 난다(`sealed`가 컴파일러에게 "이게 전부다"라고 알려주기 때문) — 이후 상태를 추가하게 되면 이 분기들을 놓치는 즉시 빌드가 깨져서 알 수 있다. enum + `switch`/`if-else`였다면 새 값을 추가해도 기존 분기 코드는 조용히 컴파일되고, 런타임에야 처리 누락이 드러났을 것이다.

```java
package com.loopers.domain.payment;

public record PaymentInfo(String idempotencyKey, Long amount, PaymentStatus status) {
}
```

`PaymentInfo`는 API 응답으로 내보낼 값만 담은 불변 객체 — 컨트롤러/Facade는 이걸로만 결과를 다루고, `PaymentModel` 자체(엔티티, 영속성 컨텍스트에 묶인 객체)는 도메인 밖으로 노출하지 않는다. 컨트롤러가 이 `PaymentInfo`를 JSON으로 내보낼 때도 `switch` 패턴 매칭으로 상태별 응답 필드를 안전하게 구성할 수 있다.

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

## 3. Redis 1차 선점 (IdempotencyLock)

`modules:redis`가 이미 `RedisTemplate<String, String>` 빈을 제공하므로(`commerce-api`는 `modules:redis`를 이미 의존 중) 별도 설정 없이 바로 사용 가능.

`SET key value NX EX ttl` — Lettuce의 `opsForValue().setIfAbsent(key, value, ttl)`이 정확히 이 원자적 명령을 감싸고 있다. "이미 있으면 실패, 없으면 선점 성공"이 한 번의 Redis 호출로 원자적으로 처리되는 게 핵심 — 이게 없으면(예: GET 후 SET을 따로 하면) 그 사이에 경쟁 상태가 생긴다.

```java
package com.loopers.infrastructure.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class IdempotencyLock {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "payment:idempotency:";

    private final RedisTemplate<String, String> redisTemplate;

    /** true면 이 요청이 최초로 선점에 성공한 것, false면 이미 다른 요청이 선점 중 */
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

**TTL을 5분으로 잡은 이유**: 선점만 하고 DB 저장 전에 서버가 죽는 경우를 대비한 자동 회수 장치. 너무 짧으면 정상 처리 중에도 락이 풀려 동시성 보장이 깨지고, 너무 길면 장애 시 복구가 느려짐 — 이 도메인의 "결제 처리에 걸리는 최대 시간"을 기준으로 잡아야 한다.

---

## 4. 멱등 처리 서비스 로직 (Redis 1차 + DB 최종 확정)

흐름: Redis로 먼저 선점 시도 → 실패하면(이미 선점됨) DB에서 기존 레코드를 찾아 반환(아직 저장 전일 수도 있으니 재시도 대상) → 성공하면 DB에 저장하되, unique constraint 위반이 나면(Redis 장애로 선점이 씹혔거나, TTL 만료 후 재요청 등 엣지 케이스) DB가 최종 심판으로 동작.

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
```

이미 승인된 기존 레코드가 조회되면 PG 재호출을 생략하는 판단은 `PaymentModel.approveOrElse(...)` 내부에서 처리된다 — Facade(step 3)는 이 메서드를 호출만 할 뿐, 상태를 직접 비교하지 않는다.

**Redis 락을 언제 해제하나**: 이 예시는 TTL 자동 만료에만 의존하고 명시적 `release()`를 호출하지 않는다 — 결제는 한 번 성공하면 같은 키로 다시 올 필요가 없으므로, 굳이 즉시 해제해서 재시도 창구를 열어줄 이유가 없다. 반대로 "재시도를 허용하고 싶은 실패 상황"이 있다면 `fail()` 처리 후 `release()`를 호출하는 경로를 추가로 설계해야 한다 — 이건 Phase 2(재시도 가능/불가능 분류)와 맞닿는 지점이라 지금은 확장 포인트로만 남겨둔다.

---

## 확인

- 같은 `idempotencyKey`로 요청을 동시에 2번 보내도 `payment` 테이블에 레코드가 1건만 생기는지 테스트로 검증 (`ExecutorService`로 동시 호출 재현)
- Redis를 잠깐 내린 상태에서 요청을 보내면 어떻게 동작하는지 확인 — `setIfAbsent`가 예외를 던지는지, 어떻게 처리할지는 직접 결정 (지금 예시엔 이 경로에 대한 예외 처리가 없다 — 의도적으로 비워둔 지점이니 직접 채워볼 것)
- `idempotencyKey` 없이 요청하면 `BAD_REQUEST` 응답 확인
- Redis 선점 성공 후 DB 저장 전에 프로세스가 죽는 상황을 가정했을 때, TTL 5분 동안 같은 키로는 재시도가 막힌다는 트레이드오프를 설명할 수 있는지 스스로 점검

다음 단계 → [phase1-step3-pg-call.md](phase1-step3-pg-call.md)
