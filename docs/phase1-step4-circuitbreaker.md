# Phase 1 - Step 4: Resilience4j Circuit Breaker 도입

> pg-simulator의 실패 확률을 의도적으로 높여서 CLOSED → OPEN → HALF_OPEN 전이를 강제 재현하고, Grafana로 관찰한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

**선행**: [phase1-step3-pg-call.md](phase1-step3-pg-call.md)

---

## 결정 지점

- **sliding window 타입**: COUNT_BASED(최근 N건) vs TIME_BASED(최근 N초) — 트래픽이 일정하지 않은 결제 API라면 COUNT_BASED가 예측하기 쉬움
- **failure-rate-threshold**: 몇 %가 실패하면 OPEN으로 갈지 — 너무 낮으면 일시적 노이즈에도 차단, 너무 높으면 장애 감지가 늦음. 실습에서는 관찰이 목적이므로 50% 근처로 설정해 전이를 쉽게 재현
- **slow-call-duration-threshold**: 몇 ms 넘으면 "느린 호출"로 간주해 실패처럼 카운트할지 — Step 3에서 잡은 `readTimeout`(3초)보다 짧게 잡아야 타임아웃 전에 감지됨

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
    public PgApproveResult approve(Long amount) {
        PgTransactionResponse response = restClient.post()
            .uri("/api/v1/transactions")
            .body(new PgTransactionRequest(amount))
            .retrieve()
            .body(PgTransactionResponse.class);

        return new PgApproveResult(response.transactionKey(), true);
    }

    // fallbackMethod는 원본과 동일한 파라미터 + Throwable을 마지막 인자로 받아야 함
    private PgApproveResult approveFallback(Long amount, Throwable t) {
        log.warn("Circuit Breaker fallback 동작, amount={}, cause={}", amount, t.getMessage());
        throw new CoreException(ErrorType.INTERNAL_ERROR, "결제 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.");
        // fallback 로직 자체는 Step 5에서 구체화 (여기선 CircuitBreaker 동작 확인이 목적)
    }
}
```

> `@Bean` 방식 대신 `@Component`로 직접 등록하는 이유: `@CircuitBreaker` 애노테이션은 스프링이 관리하는 빈에 AOP 프록시를 씌워야 동작하는데, Step 3의 `PgClientConfig`처럼 `new`로 직접 생성하면 프록시가 적용되지 않음. `baseUrl`은 `@Value`로 필드 주입하도록 변경 필요.

---

## 4. 상태 전이 관찰

```shell
./gradlew :apps:commerce-api:bootRun
```

pg-simulator의 실패율을 높여서 강제로 OPEN 전이 유도:

```shell
# pg-simulator가 항상 실패하도록 (엔드포인트에 파라미터를 고정하거나, 임시로 코드 수정)
```

또는 반복 요청 스크립트로 재현:

```shell
for i in $(seq 1 15); do
  curl -X POST "http://localhost:8081/api/v1/transactions?forceFail=true" -H "Content-Type: application/json" -d '{}' &
done
```

로그에서 `CircuitBreaker 'pg-simulator' changed state from CLOSED to OPEN` 같은 이벤트 확인 (Resilience4j가 기본으로 로그를 남기지 않으면 `CircuitBreakerRegistry`에 리스너를 등록해서 로그 추가):

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

Grafana(localhost:3000)에서 `resilience4j_circuitbreaker_state` 메트릭으로 CLOSED/OPEN/HALF_OPEN 상태를 시계열로 확인. `wait-duration-in-open-state`(10초) 경과 후 HALF_OPEN으로 넘어가고, 이후 요청이 성공하면 CLOSED로 복귀하는지까지 확인.

---

## 확인

- 정상 상태에서는 CLOSED 유지, 요청이 그대로 pg-simulator까지 도달하는지 확인
- 강제 실패를 반복하면 `minimum-number-of-calls`(5건) 이후 `failure-rate-threshold`(50%) 초과 시 OPEN으로 전이하는지 확인
- OPEN 상태에서는 pg-simulator를 호출하지 않고 즉시 fallback이 실행되는지 확인 (로그에서 실제 HTTP 호출이 안 나가는지 체크)
- `wait-duration-in-open-state` 경과 후 HALF_OPEN → (성공 시) CLOSED 복귀 확인

다음 단계 → [phase1-step5-fallback.md](phase1-step5-fallback.md)
