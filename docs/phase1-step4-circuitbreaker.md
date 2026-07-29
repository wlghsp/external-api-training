# Phase 1 - Step 4: Resilience4j Circuit Breaker 도입

> pg-simulator의 실패 확률을 의도적으로 높여서 CLOSED → OPEN → HALF_OPEN 전이를 강제 재현하고, Grafana로 관찰한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

**선행**: [phase1-step3-pg-call.md](phase1-step3-pg-call.md)

---

## 결정 지점

| 항목 | 값 | 근거 |
|---|---|---|
| sliding window 타입 | COUNT_BASED | 트래픽이 일정하지 않은 결제 API는 TIME_BASED보다 예측하기 쉬움 |
| failure-rate-threshold | 50% | 실습 목적상 전이를 쉽게 재현하려고 낮게 설정. 실제로는 너무 낮으면 노이즈에도 차단, 너무 높으면 장애 감지가 늦어짐 |
| slow-call-duration-threshold | 2s | Step 3의 `readTimeout`(3초)보다 짧게 잡아야 타임아웃 전에 "느린 호출"로 감지됨 |

---

## 1. 의존성 추가

`apps/commerce-api/build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.resilience4j:resilience4j-spring-boot3")
}
```

---

## 2. CircuitBreaker 설정

`apps/commerce-api/src/main/resources/application.yml`에 추가:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      pg-simulator:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
```

---

## 3. 클라이언트에 CircuitBreaker 적용

`@CircuitBreaker` 애노테이션은 Spring AOP 프록시를 거쳐야 동작하므로, `infrastructure` 구현체가 스프링 빈으로 등록되어 있어야 함 (step 3에서 `@Bean`으로 등록했으면 OK). `PgTransactionRequest`/`PgTransactionResponse`는 step 3에서 정의한 record를 그대로 사용.

**중요 — 실패를 요청 파라미터로 제어할 수 있어야 한다.** Circuit Breaker의 상태(CLOSED/OPEN/HALF_OPEN)는 JVM 메모리에만 존재하고 재시작하면 무조건 CLOSED로 초기화된다. 즉 "코드를 고쳐서 강제 실패 → 재시작 → 관찰 → 코드를 되돌려서 강제 실패 해제 → 재시작 → 관찰"처럼 **재시작을 끼워 넣는 재현 방식은 상태 전이 자체를 볼 수 없다.** 재시작 없이 같은 서버 프로세스 안에서 "강제 실패 → 정상"을 오갈 수 있도록, `approve`가 실패 여부를 요청마다 파라미터로 받게 만든다.

```java
package com.loopers.domain.payment;

public interface PgClient {
    PgApproveResult approve(Long amount, boolean forceFail);
}
```

```java
package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PgApproveResult;
import com.loopers.domain.payment.PgClient;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class PgSimulatorClient implements PgClient {

    private final RestClient restClient;

    public PgSimulatorClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "pg-simulator", fallbackMethod = "approveFallback")
    public PgApproveResult approve(Long amount, boolean forceFail) {
        String uri = forceFail ? "/api/v1/transactions?forceFail=true" : "/api/v1/transactions";
        PgTransactionResponse response = restClient.post()
            .uri(uri)
            .body(new PgTransactionRequest(amount))
            .retrieve()
            .body(PgTransactionResponse.class);

        return new PgApproveResult(response.transactionKey(), true);
    }

    // fallbackMethod는 원본과 동일한 파라미터 + Throwable을 마지막 인자로 받아야 함
    private PgApproveResult approveFallback(Long amount, boolean forceFail, Throwable t) {
        log.warn("Circuit Breaker fallback 동작, amount={}, cause={}", amount, t.getMessage());
        throw new CoreException(ErrorType.INTERNAL_ERROR, "결제 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.");
        // fallback 로직 자체는 Step 5에서 구체화 (여기선 CircuitBreaker 동작 확인이 목적)
    }
}
```

`forceFail`을 컨트롤러까지 관통시킨다 (테스트 전용 파라미터이므로 프로덕션에 남기지 않고 Phase 1 학습이 끝나면 제거하거나, 프로필로 막는 것도 고려할 지점):

```java
// PaymentFacade.charge — Step 3에서 만든 메서드에 forceFail 파라미터 추가
@Transactional
public PaymentInfo charge(String idempotencyKey, Long amount, boolean forceFail) {
    PaymentModel payment = paymentService.getOrCreate(idempotencyKey, amount);
    payment.approveOrElse(() -> pgClient.approve(amount, forceFail));
    return payment.toInfo();
}
```

```java
// PaymentV1Controller.charge — 쿼리 파라미터로 받는다
@PostMapping
@Override
public ApiResponse<PaymentV1Dto.ChargeResponse> charge(
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestBody PaymentV1Dto.ChargeRequest request,
    @RequestParam(required = false, defaultValue = "false") boolean forceFail
) {
    PaymentInfo info = paymentFacade.charge(idempotencyKey, request.amount(), forceFail);
    return ApiResponse.success(PaymentV1Dto.ChargeResponse.from(info));
}
```

> `@CircuitBreaker`는 Spring이 관리하는 빈이어야 AOP 프록시가 적용된다. Step 3의 `PgClientConfig.@Bean` 메서드가 `new PgSimulatorClient(...)`를 반환하는 방식 그대로 유지해도 된다 — `@Bean`으로 등록된 반환값도 컨테이너가 관리하는 빈이라 프록시 적용 대상이며, 실제로 상태 전이 로그(CLOSED→OPEN→HALF_OPEN→CLOSED)가 정상 동작하는 것으로 확인됨. `PgSimulatorClient`에 `@Component`를 별도로 붙일 필요는 없다.

---

## 4. 상태 전이 관찰

**1단계 — commerce-api, pg-simulator 둘 다 기동** (이후 재시작 없음 — 재시작하면 상태가 CLOSED로 초기화되므로 절대 중간에 재시작하지 않는다)
```shell
./gradlew :apps:pg-simulator:bootRun    # 터미널 1
./gradlew :apps:commerce-api:bootRun    # 터미널 2
```

**2단계 — `forceFail=true`로 반복 요청을 보내 OPEN 전이 유도**
```shell
for i in $(seq 1 15); do
  curl -X POST "http://localhost:8080/api/v1/payments?forceFail=true" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: cb-test-$i" \
    -d '{"amount": 1000}' &
done
```
매번 다른 `Idempotency-Key`를 써야 한다 — 같은 키면 step 2의 멱등 처리가 재호출 자체를 막아서 Circuit Breaker에 호출이 쌓이지 않는다.

**Resilience4j는 상태 전이를 기본적으로 로그에 남기지 않는다.** 아래 `CircuitBreakerEventLogger`를 먼저 추가해야 `CircuitBreaker 상태 전이: ...` 로그가 찍힌다 — 이 클래스 없이는 2단계를 반복해도 콘솔에 아무 신호가 안 보이는 게 정상이다.

```java
package com.loopers.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventLogger {

    private final CircuitBreakerRegistry registry;

    @PostConstruct
    public void registerListeners() {
        registry.circuitBreaker("pg-simulator").getEventPublisher()
            .onStateTransition(event ->
                log.warn("CircuitBreaker 상태 전이: {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
    }
}
```

Grafana(localhost:3000)에서 `resilience4j_circuitbreaker_state` 메트릭으로 CLOSED/OPEN/HALF_OPEN 상태를 시계열로 확인. `wait-duration-in-open-state`(10초) 경과 후 HALF_OPEN으로 넘어가는데, 이 상태에서 **`forceFail` 없이(정상) 요청**을 보내야 CLOSED로 복귀한다:

```shell
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: cb-recovery-001" \
  -d '{"amount": 1000}'
```

(같은 절차를 아래 "확인" 섹션 4번 항목에서 다시 다룬다 — 재시작 없이 진행해야 한다는 점은 거기서 확인)

---

## 확인

**모든 항목을 서버 재시작 없이, 같은 프로세스 안에서 순서대로 진행한다.**

| # | 확인 항목 | 방법 | 기대 결과 |
|---|---|---|---|
| 1 | CLOSED 정상 동작 | `forceFail=false`(기본값)로 요청 | 요청이 그대로 pg-simulator까지 도달, CLOSED 유지 |
| 2 | OPEN 전이 | 위 2단계대로 `forceFail=true` 반복 | `minimum-number-of-calls`(5건) 이후 `failure-rate-threshold`(50%) 초과 시 OPEN 전이 로그 |
| 3 | OPEN 상태의 단락(short-circuit) | OPEN 전이 직후 `forceFail=true`로 추가 요청 | pg-simulator에 실제 HTTP 요청이 안 나가고 즉시 fallback 실행 (pg-simulator 콘솔에 로그가 안 찍히는지로 확인) |
| 4 | HALF_OPEN 복귀 | 아래 절차 | HALF_OPEN → 판정 후 CLOSED로 복귀 |

**HALF_OPEN 판정 규칙**: `permitted-number-of-calls-in-half-open-state: 3`이므로, HALF_OPEN 진입 후 **딱 3건**의 요청 결과로 판정한다 — 3건보다 적으면 아직 판정이 안 나고, 3건을 넘는 요청은 이미 CLOSED/OPEN이 정해진 뒤라 의미가 없다. 3건 중 실패율이 `failure-rate-threshold`(50%) 이하면 CLOSED, 초과하면 다시 OPEN. 즉 3건 중 **2건만 성공해도** 실패율 33%로 CLOSED 복귀, 1건만 성공하면 실패율 66%로 다시 OPEN.

**4번 항목 절차 (재시작 없이, 정상 요청 3건 보내기):**

1. `wait-duration-in-open-state`(10초) 만큼 기다린다 — 코드 수정도, 재시작도 하지 않는다
2. 새 `Idempotency-Key` 3개로, `forceFail` 없이(또는 `forceFail=false`) 요청을 3번 보낸다
   ```shell
   for i in 1 2 3; do
     curl -i -X POST http://localhost:8080/api/v1/payments \
       -H "Content-Type: application/json" \
       -H "Idempotency-Key: cb-recovery-$i" \
       -d '{"amount": 1000}'
   done
   ```
3. 3건이 모두 성공하면 로그에서 `HALF_OPEN` 다음 `CLOSED`로의 전이가 찍히는지 확인 (`CircuitBreakerEventLogger`가 남기는 로그)

확인이 모두 끝나면 `forceFail` 파라미터는 프로덕션에 남기지 않는 게 원칙이므로, `PgClient`/`PaymentFacade`/`PaymentV1Controller`에서 제거할지 프로필로 제한할지 직접 판단한다.

다음 단계 → [phase1-step5-fallback.md](phase1-step5-fallback.md)
