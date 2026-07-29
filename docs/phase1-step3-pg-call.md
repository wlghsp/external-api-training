# Phase 1 - Step 3: commerce-api → pg-simulator 호출 흐름

> 아직 Circuit Breaker 없이 순수 호출만 붙여서, 실패/지연이 로그에 어떻게 나타나는지 먼저 관찰한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

**선행**: [phase1-step2-idempotency.md](phase1-step2-idempotency.md)

---

## 결정 지점

| 항목 | 선택 | 근거 |
|---|---|---|
| 레이어 위치 | `infrastructure`에 클라이언트, `domain`엔 인터페이스만 | `ExampleRepository`/`ExampleRepositoryImpl` 패턴과 동일 |
| HTTP 클라이언트 | `RestClient` | `RestTemplate`은 유지보수 모드, `WebClient`는 리액티브 스택 없이 쓰면 과함. 왜 Feign이 아닌지는 [phase1-step3-restclient-vs-feign.md](phase1-step3-restclient-vs-feign.md) 참고 |
| 타임아웃 값 | connect 2s / read 3s (러프하게) | Step 4에서 Circuit Breaker `slow-call-duration-threshold`와 맞춰 재조정 예정 |

---

## 1. PG 클라이언트 인터페이스 (domain)

`PgApproveResult`는 step 2에서 이미 정의했다(`PaymentModel.approveOrElse`가 이 타입을 받으므로 도메인 쪽에서 먼저 정의됨). 여기서는 그 결과를 실제로 만들어내는 인터페이스만 추가한다.

```java
package com.loopers.domain.payment;

public interface PgClient {
    PgApproveResult approve(Long amount);
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
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
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
  base-url: http://localhost:8082
```

---

## 4. Facade에서 멱등 처리 + PG 호출 orchestration

Facade는 "이미 승인됐는지" 스스로 판단하지 않는다. `PaymentModel.approveOrElse(...)`에게 "필요하면 이 콜백으로 승인해라"고 지시할 뿐이다 — 상태별 분기(`switch (status())`)는 도메인 객체 안으로 이미 옮겨졌다(step 2, `PaymentStatus` sealed interface).

```java
package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentInfo;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PgClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentService paymentService;
    private final PgClient pgClient;

    @Transactional
    public PaymentInfo charge(String idempotencyKey, Long amount) {
        PaymentModel payment = paymentService.getOrCreate(idempotencyKey, amount);
        payment.approveOrElse(() -> pgClient.approve(amount));
        return payment.toInfo();
    }
}
```

---

## 5. 확인용 컨트롤러

지금까지는 `PaymentFacade`까지만 만들었고 이걸 호출할 진입점이 없다. 이 프로젝트는 컨트롤러를 인터페이스 기반으로 선언한다 — `ExampleV1ApiSpec`/`ExampleV1Controller`/`ExampleV1Dto` 3분할 패턴을 그대로 따른다: API 스펙(Swagger 문서화용 인터페이스)과 실제 구현(매핑 애노테이션)을 분리하고, 요청/응답 DTO는 중첩 record로 묶어 `from(Info)` 정적 팩토리로 변환한다. 응답은 `ApiResponse<T>`로 감싼다.

```java
package com.loopers.interfaces.api.payment;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Payment V1 API", description = "결제 API 입니다.")
public interface PaymentV1ApiSpec {

    @Operation(
        summary = "결제 요청",
        description = "Idempotency-Key 헤더로 멱등성을 보장하며 결제를 요청합니다."
    )
    ApiResponse<PaymentV1Dto.ChargeResponse> charge(
        String idempotencyKey,
        PaymentV1Dto.ChargeRequest request
    );
}
```

```java
package com.loopers.interfaces.api.payment;

import com.loopers.domain.payment.PaymentInfo;
import com.loopers.domain.payment.PaymentStatus;

public class PaymentV1Dto {
    public record ChargeRequest(Long amount) {}

    public record ChargeResponse(String idempotencyKey, Long amount, PaymentStatus status) {
        public static ChargeResponse from(PaymentInfo info) {
            return new ChargeResponse(info.idempotencyKey(), info.amount(), info.status());
        }
    }
}
```

```java
package com.loopers.interfaces.api.payment;

import com.loopers.application.payment.PaymentFacade;
import com.loopers.domain.payment.PaymentInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentV1Controller implements PaymentV1ApiSpec {

    private final PaymentFacade paymentFacade;

    @PostMapping
    @Override
    public ApiResponse<PaymentV1Dto.ChargeResponse> charge(
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody PaymentV1Dto.ChargeRequest request
    ) {
        PaymentInfo info = paymentFacade.charge(idempotencyKey, request.amount());
        return ApiResponse.success(PaymentV1Dto.ChargeResponse.from(info));
    }
}
```

---

## 확인

두 애플리케이션을 각각 별도 터미널(또는 IntelliJ 실행 설정 두 개)로 동시에 띄워야 한다 — pg-simulator가 안 떠 있으면 아래 확인 전부 의미가 없다.

```shell
# 터미널 1
./gradlew :apps:pg-simulator:bootRun
# "Tomcat started on port 8082" 로그 확인

# 터미널 2
./gradlew :apps:commerce-api:bootRun
# "Tomcat started on port 8080" 로그 확인
```

### 1) 정상 승인 확인

```shell
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{"amount": 10000}'
```

- **기대 응답**: `200 OK`, `ApiResponse` 래핑 구조(`{"meta":{"result":"SUCCESS",...},"data":{"idempotencyKey":"test-key-001","amount":10000,"status":{...}}}`) — `status`는 `PaymentStatus`가 sealed interface라 잭슨 기본 직렬화로는 어떤 하위 타입인지(`Approved`/`Pending`/`Failed`) 필드만으로 구분이 안 될 수 있다. 실제 JSON 출력을 보고 `Approved`에 담긴 `transactionKey` 값이 보이는지 직접 확인할 것 — 애매하면 `@JsonTypeInfo` 등으로 타입 정보를 명시할지 여부를 스스로 판단해본다
- **확인할 로그**: commerce-api 콘솔에 이 요청과 관련된 별다른 에러 로그가 없어야 함. pg-simulator 콘솔에는 `PG 시뮬레이터 - 결제 승인, transactionKey=..., amount=10000` 로그가 찍혀야 함
- **DB 확인**: `payment` 테이블에 `idempotency_key = test-key-001`인 행이 1건 생겼는지, `status_type = APPROVED`이고 `transaction_key`가 채워졌는지 직접 조회해서 확인 (MySQL 클라이언트 또는 IntelliJ Database 탭)

### 2) 멱등성 재확인 (같은 키로 재요청)

```shell
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-001" \
  -d '{"amount": 10000}'
```

- **기대 응답**: 1)과 동일한 `transactionKey`가 그대로 반환 (PG 재호출 없음)
- **확인할 로그**: pg-simulator 콘솔에 새로운 승인 로그가 **찍히지 않아야** 함 — 찍히면 `approveOrElse`가 제대로 동작 안 하는 것

### 3) 지연 → ReadTimeout 확인

**검증 목표**: PG가 3초 넘게 응답하지 않으면 commerce-api가 무한정 기다리지 않고 포기하는가 (`PgClientConfig`의 `readTimeout: 3초` 설정이 실제로 동작하는가).

**문제**: pg-simulator는 `?delayMs=N`으로 지연을 흉내낼 수 있지만(step 1), commerce-api가 pg-simulator를 호출하는 코드(`PgSimulatorClient.approve`)는 이 파라미터를 전달하는 기능이 없다. 그래서 이 파라미터를 코드에 **잠깐 하드코딩**해서 확인하고, 확인 후 원복한다.

**1단계 — pg-simulator 단독으로 지연이 되는지 먼저 확인** (commerce-api 거치지 않음)
```shell
curl -i -X POST "http://localhost:8082/api/v1/transactions?delayMs=5000" \
  -H "Content-Type: application/json" -d '{"amount": 10000}'
```
5초 뒤에 응답이 오면 정상.

**2단계 — `PgSimulatorClient.java`를 이 확인을 위해서만 임시로 수정**
```java
// 기존
.uri("/api/v1/transactions")
// 임시 (확인 후 반드시 되돌릴 것)
.uri("/api/v1/transactions?delayMs=5000")
```

**3단계 — commerce-api 재시작 후, `PaymentV1Controller`(위 5번에서 만든 컨트롤러)로 결제 요청**
```shell
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: timeout-test-001" \
  -d '{"amount": 10000}'
```
- **기대 결과**: pg-simulator는 5초 있다가 응답하려 하지만, commerce-api가 `readTimeout: 3초`이므로 3초 시점에 먼저 포기하고 `500 Internal Server Error` 반환 (지금 단계엔 Circuit Breaker가 없어 예외가 그대로 전파됨 — Step 4에서 이걸 막는다)
- **확인할 로그**: commerce-api 콘솔에 `PG 승인 요청 실패: ...` 경고 로그와 함께 타임아웃 관련 예외 스택트레이스(`SocketTimeoutException` 또는 유사한 원인)

**4단계 — 확인이 끝나면 `PgSimulatorClient.java`의 `?delayMs=5000`을 지우고 원래 코드로 되돌린다.**

### 4) 강제 실패 확인

**검증 목표**: PG가 실패 응답을 주면 commerce-api가 `CoreException`으로 변환해서 명확히 실패로 응답하는가.

**1단계 — pg-simulator 단독으로 강제 실패가 되는지 먼저 확인**
```shell
curl -i -X POST "http://localhost:8082/api/v1/transactions?forceFail=true" \
  -H "Content-Type: application/json" -d '{"amount": 5000}'
```
`503`이 오면 정상.

**2단계 — `PgSimulatorClient.java`를 임시로 수정** (3번 항목과 동일한 방식)
```java
.uri("/api/v1/transactions?forceFail=true")
```

**3단계 — commerce-api 재시작 후 결제 요청**
```shell
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-key-002" \
  -d '{"amount": 5000}'
```
- **기대 응답**: `500 Internal Server Error`, 바디에 `CoreException` 메시지("PG 승인에 실패했습니다.")
- **확인할 로그**: commerce-api 콘솔에 `PG 승인 요청 실패: ...` 경고 로그

**4단계 — 확인이 끝나면 `?forceFail=true`를 지우고 원래 코드로 되돌린다.**

이 네 가지가 전부 기대대로 동작하면 다음 단계로 → [phase1-step4-circuitbreaker.md](phase1-step4-circuitbreaker.md)
