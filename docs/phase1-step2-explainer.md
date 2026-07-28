# Phase 1 - Step 2 설명: 왜 멱등 처리를 하는가, Redis SETNX + DB unique constraint는 어떻게 맞물리는가

> `phase1-step2-idempotency.md`의 구현 가이드와 별개로, "왜 이걸 하는지 / 실제로 어떻게 동작하는지"를 풀어서 설명하는 문서. 블로그 발행 시 이 내용을 기반으로 써도 된다.

---

## 왜 이걸 하는가 — 문제 상황

결제 API는 클라이언트가 "요청을 보냈는데 응답을 못 받는" 상황(타임아웃, 네트워크 끊김)이 흔하다. 이때 클라이언트는 결제가 실제로 처리됐는지 몰라서 **같은 요청을 재시도**한다. 서버가 이걸 그대로 다시 처리하면 같은 결제가 두 번 일어난다 — 돈이 두 번 빠져나가는 사고다.

이걸 막는 게 **멱등성(Idempotency)**이다: "같은 요청을 몇 번을 보내도 결과는 한 번만 일어난 것과 같아야 한다."

## 어떻게 구현했나 — Redis SETNX + DB unique constraint 이중 구조

핵심 질문은 "이 `idempotencyKey`로 온 요청이 최초인지, 아니면 이미 처리 중/처리된 재시도인지"를 어떻게 판단하냐다. 여기에 **두 개의 방어선**을 뒀다.

### 1차 방어선: Redis SETNX (`IdempotencyLock`)

```java
public boolean acquire(String idempotencyKey) {
    Boolean acquired = redisTemplate.opsForValue()
        .setIfAbsent(KEY_PREFIX + idempotencyKey, "1", LOCK_TTL);
    return Boolean.TRUE.equals(acquired);
}
```

`setIfAbsent`는 Redis의 `SET key value NX EX ttl` 명령을 감싼 것이다. **"키가 없으면 설정하고 성공(true), 이미 있으면 아무것도 안 하고 실패(false)"** — 이 확인과 설정이 Redis 안에서 원자적으로 한 번에 일어난다. "GET으로 확인한 다음 SET한다"처럼 두 단계로 나누면 그 사이에 다른 요청이 끼어들 틈(경쟁 상태)이 생기는데, SETNX는 그 틈 자체가 없다.

즉 같은 `idempotencyKey`로 동시에 두 요청이 와도, Redis 레벨에서 **딱 하나만 `acquire()`가 true를 받는다.**

### 2차 방어선: DB unique constraint

```java
@Table(name = "payment", uniqueConstraints = {
    @UniqueConstraint(columnNames = "idempotency_key")
})
```

`idempotency_key` 컬럼에 DB 유니크 제약을 걸어뒀다. 이건 Redis가 실패하는 상황(장애, 네트워크 문제, TTL 만료 후 재시도 등)에 대비한 **최종 안전망**이다. Redis가 어떤 이유로든 중복을 막지 못해도, DB에 같은 키로 두 번 INSERT하려 하면 DB 자체가 거부한다.

### 두 방어선이 실제로 어떻게 맞물리는가 (`PaymentService`)

```java
@Transactional
public PaymentModel getOrCreate(String idempotencyKey, Long amount) {
    boolean acquired = idempotencyLock.acquire(idempotencyKey);
    if (!acquired) {
        // Redis 기준 이미 처리 중/처리됨 - DB에서 실제 상태를 확인
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(...);
    }
    return createNew(idempotencyKey, amount);
}

private PaymentModel createNew(String idempotencyKey, Long amount) {
    try {
        return paymentRepository.save(new PaymentModel(idempotencyKey, amount));
    } catch (DataIntegrityViolationException e) {
        // Redis 선점은 성공했는데 DB에 이미 존재 - Redis 장애/TTL 만료로 1차 방어가 뚫린 경우
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(...);
    }
}
```

흐름을 순서대로 보면:

1. **Redis 선점 시도** — 성공하면 "내가 최초다"라고 믿고 DB에 새로 저장하러 감
2. **Redis 선점 실패** — 이미 누군가 처리 중이거나 끝났다는 뜻이므로, DB에서 기존 레코드를 찾아 그대로 반환 (재시도 요청이 최초 처리 결과를 그대로 받게 됨)
3. **Redis는 선점 성공했는데 DB에 실제 저장하려니 unique constraint 위반** — 이건 Redis가 신뢰할 수 없었던 엣지 케이스(Redis 장애 중이었거나, TTL 5분이 지나서 락이 이미 풀렸는데 그 사이 실제로는 이미 DB에 저장된 경우 등)다. 이때는 예외를 잡고 DB에서 다시 찾아 반환 — **DB가 최종 심판**이 되는 지점이다.

## 왜 하나만 쓰지 않았나

- **DB unique constraint만 쓰면**: 매번 INSERT를 시도하고 실패(예외)로 중복을 감지해야 해서, 트래픽이 몰리면 불필요한 쓰기 시도와 예외 처리 비용이 쌓인다.
- **Redis SETNX만 쓰면**: 빠르지만 Redis가 장애 나는 순간 멱등 보장이 통째로 사라진다. 결제처럼 돈이 걸린 도메인에서 이 리스크를 혼자 감수하긴 부담스럽다.

그래서 **Redis로 대부분의 중복 요청을 빠르게 걸러내고(1차), DB가 절대 안전망 역할을 하는(최종)** 이중 구조로 갔다 — 실무에서 고트래픽 결제/주문 API가 흔히 쓰는 패턴이기도 하다.

---

관련 문서: [phase1-step2-idempotency.md](phase1-step2-idempotency.md) (구현 가이드) · [phase1-quiz.md](phase1-quiz.md) (이해도 테스트)
