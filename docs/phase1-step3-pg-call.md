# Phase 1 - Step 3: commerce-api → pg-simulator 호출 흐름

> 아직 Circuit Breaker 없이 순수 호출만 붙여서, 실패/지연이 로그에 어떻게 나타나는지 먼저 관찰한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

**선행**: [phase1-step2-idempotency.md](phase1-step2-idempotency.md)

---

## 결정 지점

- **레이어 위치**: 외부 시스템 호출이므로 `infrastructure`에 클라이언트를 두고, `domain`에는 인터페이스만 정의 (`ExampleRepository`/`ExampleRepositoryImpl` 패턴과 동일)
- **HTTP 클라이언트 선택**: Spring Boot 3.4 기준 `RestClient` 권장 (`RestTemplate`은 유지보수 모드, `WebClient`는 리액티브 스택 없이 쓰면 과함)
- **타임아웃 값**: 이 단계에서는 러프하게 잡아도 됨 — Step 4에서 Circuit Breaker의 `slow-call-duration-threshold`와 맞춰 재조정

---

## 1. PG 클라이언트 인터페이스 (domain)

```java
package com.loopers.domain.payment;

public interface PgClient {
    PgApproveResult approve(Long amount);
}
```

```java
package com.loopers.domain.payment;

public record PgApproveResult(String transactionKey, boolean approved) {
}
```

---

## 2. PG 클라이언트 구현체 (infrastructure)

pg-simulator와 주고받는 페이로드도 `Map<String, Object>` 대신 record로 명시 — 응답 필드가 바뀌면 컴파일 타임에 알 수 있고, `(String) response.get(...)` 같은 캐스팅이 사라진다.

```java
package com.loopers.infrastructure.payment;

record PgTransactionRequest(Long amount) {}
```

```java
package com.loopers.infrastructure.payment;

record PgTransactionResponse(String transactionKey, String status) {}
```

```java
package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PgApproveResult;
import com.loopers.domain.payment.PgClient;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

@Slf4j
public class PgSimulatorClient implements PgClient {

    private final RestClient restClient;

    public PgSimulatorClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public PgApproveResult approve(Long amount) {
        try {
            PgTransactionResponse response = restClient.post()
                .uri("/api/v1/transactions")
                .body(new PgTransactionRequest(amount))
                .retrieve()
                .body(PgTransactionResponse.class);

            return new PgApproveResult(response.transactionKey(), true);
        } catch (Exception e) {
            log.warn("PG 승인 요청 실패: {}", e.getMessage());
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 승인에 실패했습니다.");
        }
    }
}
```

---

## 3. 설정 (RestClient Bean + 타임아웃)

```java
package com.loopers.config;

import com.loopers.domain.payment.PgClient;
import com.loopers.infrastructure.payment.PgSimulatorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class PgClientConfig {

    @Bean
    public PgClient pgClient(
        RestClient.Builder builder,
        @Value("${pg-simulator.base-url}") String baseUrl
    ) {
        var factory = ClientHttpRequestFactoryBuilder.detect()
            .build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(3)));

        return new PgSimulatorClient(builder.requestFactory(factory), baseUrl);
    }
}
```

`application.yml`에 추가:

```yaml
pg-simulator:
  base-url: http://localhost:8081
```

---

## 4. Facade에서 멱등 처리 + PG 호출 orchestration

```java
package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.PgApproveResult;
import com.loopers.domain.payment.PgClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final PgClient pgClient;

    public PaymentInfo charge(String idempotencyKey, Long amount) {
        PaymentModel payment = paymentService.getOrCreate(idempotencyKey, amount);

        if (payment.getStatus() == PaymentStatus.APPROVED) {
            return PaymentInfo.from(payment); // 이미 처리됨 - PG 재호출 없이 기존 결과 반환
        }

        PgApproveResult result = pgClient.approve(amount);
        payment.approve(result.transactionKey());
        return PaymentInfo.from(payment);
    }
}
```

`PaymentInfo`는 `ExampleInfo` 패턴을 따라 도메인 모델 → API 응답 DTO 변환용으로 별도 생성.

---

## 확인

- pg-simulator를 정상 응답 상태로 두고 결제 요청 → 승인 확인
- pg-simulator에 `delayMs`를 크게 줘서 `RestClient`가 `ReadTimeout`으로 실패하는지 확인 (이 시점엔 그대로 500 에러가 사용자에게 노출됨 — Step 4에서 이걸 막음)
- pg-simulator에 `forceFail=true`로 강제 실패 응답 시 `CoreException`이 잡히는지 확인

다음 단계 → [phase1-step4-circuitbreaker.md](phase1-step4-circuitbreaker.md)
