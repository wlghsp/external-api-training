# Phase 1 - Step 5: Fallback 설계

> Fallback이 "에러를 조용히 숨기고 200을 반환"하는 방식이 되지 않도록, 알람/메트릭과 함께 설계한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

**선행**: [phase1-step4-circuitbreaker.md](phase1-step4-circuitbreaker.md)

---

## 결정 지점

| 항목 | 선택 | 근거 |
|---|---|---|
| 사용자 응답 | 명확한 실패(503 등), "일단 성공"처럼 위장하지 않음 | 결제 도메인 특성상 재시도를 유도해야 함 — step 4의 fallback이 `CoreException`을 던지는 이유 |
| 조용한 실패 방지 | 메트릭 카운터 필수, 알람은 선택 | fallback 실행 시 최소한 관측 가능해야 함 |
| Slack 알람 연동 | 로그(`WARN`)로 대체, 실제 연동은 생략 | `supports/logging`에 Slack appender가 이미 있지만, 완료 기준("알람과 함께 설계할 수 있다")은 연동 여부와 무관하게 설계 근거 설명으로 충족 가능 |

---

## 1. Fallback 전용 메트릭 추가

`supports/monitoring`이 Micrometer를 이미 설정해뒀다고 가정하고, `MeterRegistry`로 커스텀 카운터를 등록:

```java
package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PgApproveResult;
import com.loopers.domain.payment.PgClient;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class PgSimulatorClient implements PgClient {

    private final RestClient restClient;
    private final Counter fallbackCounter;

    public PgSimulatorClient(RestClient.Builder builder, String baseUrl, MeterRegistry meterRegistry) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.fallbackCounter = Counter.builder("pg_client.fallback")
            .description("PG 클라이언트 CircuitBreaker fallback 실행 횟수")
            .tag("target", "pg-simulator")
            .register(meterRegistry);
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

    private PgApproveResult approveFallback(Long amount, Throwable t) {
        fallbackCounter.increment();
        log.warn("Circuit Breaker fallback 동작, amount={}, cause={}", amount, t.getMessage());
        throw new CoreException(ErrorType.INTERNAL_ERROR, "결제 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.");
    }
}
```

Grafana에서 `pg_client_fallback_total` 메트릭을 패널로 추가해 fallback 발생 빈도를 시계열로 확인할 수 있음.

---

## 2. 알람 연동 (선택)

`supports/logging`의 Slack appender를 사용하려면, fallback 로그 레벨을 `WARN`으로 유지하고 해당 로거에 대해 Slack appender가 활성화되어 있는지 `logback.xml` 설정을 확인. 이미 `slack-log-{profile}.xml`이 구성되어 있으므로, 새 로거를 추가할 필요 없이 기존 `WARN` 이상 로그가 이미 Slack으로 전파되는지 로그 설정을 확인하는 것이 첫 단계.

실습에서 Slack Webhook 없이도, 로그와 Grafana 메트릭만으로 "조용한 실패가 아니다"라는 완료 기준은 충족 가능 — 실제 알람 연동은 선택 사항으로 남겨도 됨.

---

## 3. 재시도 안내 응답 예시

`ApiControllerAdvice`가 이미 `CoreException`을 처리하므로 별도 핸들러는 필요 없음. 다만 fallback에서 던지는 예외를 `ErrorType.INTERNAL_ERROR` 대신 별도 타입(예: `SERVICE_UNAVAILABLE`)으로 분리하면 클라이언트가 "일시적 장애"와 "서버 버그"를 구분할 수 있음:

```java
// ErrorType.java에 추가 고려
SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "결제 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요."),
```

---

## 확인

| # | 확인 항목 | 방법 | 기대 결과 |
|---|---|---|---|
| 1 | fallback 메트릭 증가 | Circuit Breaker OPEN 상태에서 요청 | `pg_client_fallback_total` 메트릭 증가 (Grafana 또는 `/actuator/prometheus` 응답으로 확인) |
| 2 | 응답 상태 코드 | 위와 동일 | 200이 아닌 명확한 실패 상태 코드(503 등) |
| 3 | 실패 원인 로깅 | 위와 동일 | 로그에 `Throwable` 메시지가 남는지 |

---

## Phase 1 완료 체크

| # | 완료 기준 (`TRAINING_ROADMAP.md`) | 관련 문서 |
|---|---|---|
| 1 | Idempotency Key를 어디서 생성하고 어디까지 전파할지 설계 근거 | step 2 |
| 2 | Redis SETNX + DB unique constraint 이중화를 선택한 이유 | step 2, [explainer](phase1-step2-explainer.md) |
| 3 | CLOSED → OPEN → HALF_OPEN 상태 전이를 로그/Grafana로 관찰하고 튜닝 근거 설명 | step 4 |
| 4 | Fallback이 조용한 실패가 되지 않도록 한 설계 | step 5 |

문서 완성 → 블로그 발행 → 이해도 테스트([phase1-quiz.md](phase1-quiz.md)) 순서 (로드맵 규칙, 발행 전 테스트 금지).
