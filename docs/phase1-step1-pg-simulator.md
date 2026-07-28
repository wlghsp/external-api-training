# Phase 1 - Step 1: pg-simulator 모듈 뼈대

> 외부 PG사 역할을 하는 간단한 Spring 앱. 지연/실패를 인위로 만들 수 있어야 한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

---

## 결정 지점

- **지연을 흉내낼 방법**: 고정 sleep vs 요청 파라미터로 지연 시간(ms) 지정 → 재현성을 위해 파라미터 방식 권장
- **실패를 흉내낼 방법**: 랜덤 확률 vs 요청 파라미터/헤더로 강제 지정 → 실습 초반엔 강제 지정이 디버깅하기 쉽고, Circuit Breaker 단계(step 4)에서는 확률 방식이 sliding window를 관찰하기 좋음. 둘 다 지원하는 것도 방법
- **포트**: `8081`로 고정. `commerce-api`는 `application.yml`에 별도 지정이 없어 Spring Boot 기본값(8080)을 쓰고, `docker/infra-compose.yml`·`docker/monitoring-compose.yml`이 점유한 포트는 3000/3306/6379/6380/9090/9092/9099 — 8081은 이 중 어디와도 겹치지 않음

---

## 1. 모듈 등록

`settings.gradle.kts`에 추가:

```kotlin
include(
    ":apps:commerce-api",
    ":apps:commerce-streamer",
    ":apps:commerce-batch",
    ":apps:pg-simulator",   // 추가
    ":modules:jpa",
    ":modules:redis",
    ":modules:kafka",
    ":supports:jackson",
    ":supports:logging",
    ":supports:monitoring",
)
```

디렉토리 구조:

```
apps/pg-simulator/
├── build.gradle.kts
└── src/main/
    ├── java/com/loopers/PgSimulatorApplication.java
    └── resources/application.yml
```

`apps/pg-simulator/build.gradle.kts`는 다른 apps 모듈과 동일하게 최소 구성이면 됨 (루트 `subprojects` 블록이 Lombok, Validation, Jackson 등 공통 의존성을 이미 적용함):

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

`apps/pg-simulator/src/main/resources/application.yml`:

```yaml
server:
  port: 8081

spring:
  application:
    name: pg-simulator
```

---

## 2. 요청/응답 DTO

`Map<String, Object>`는 타입 안정성이 없고 API 계약이 코드로 드러나지 않으므로 record로 명시:

```java
package com.loopers.interfaces.api;

public record TransactionRequest(Long amount) {}
```

```java
package com.loopers.interfaces.api;

public record TransactionResponse(String transactionKey, String status) {}
```

```java
package com.loopers.interfaces.api;

public record ErrorResponse(String message) {}
```

---

## 3. 애플리케이션 진입점

```java
package com.loopers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PgSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(PgSimulatorApplication.class, args);
    }
}
```

---

## 4. 결제 요청 엔드포인트 (지연/실패 시뮬레이션)

```java
package com.loopers.interfaces.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
public class TransactionController {

    @PostMapping("/api/v1/transactions")
    public ResponseEntity<?> charge(
        @RequestBody TransactionRequest request,
        @RequestParam(required = false) Long delayMs,
        @RequestParam(required = false) Boolean forceFail,
        @RequestParam(required = false, defaultValue = "0") double failureRate
    ) {
        if (delayMs != null && delayMs > 0) {
            sleep(delayMs);
        }

        boolean shouldFail = Boolean.TRUE.equals(forceFail)
            || ThreadLocalRandom.current().nextDouble() < failureRate;

        if (shouldFail) {
            log.warn("PG 시뮬레이터 - 강제 실패 응답");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("PG 응답 실패 (시뮬레이션)"));
        }

        String transactionKey = UUID.randomUUID().toString();
        log.info("PG 시뮬레이터 - 결제 승인, transactionKey={}, amount={}", transactionKey, request.amount());
        return ResponseEntity.ok(new TransactionResponse(transactionKey, "APPROVED"));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

---

## 확인

```shell
./gradlew :apps:pg-simulator:bootRun
```

```shell
# 정상 응답
curl -X POST http://localhost:8081/api/v1/transactions -H "Content-Type: application/json" -d '{}'

# 지연 재현
curl -X POST "http://localhost:8081/api/v1/transactions?delayMs=3000" -H "Content-Type: application/json" -d '{}'

# 강제 실패
curl -X POST "http://localhost:8081/api/v1/transactions?forceFail=true" -H "Content-Type: application/json" -d '{}'
```

지연/실패 응답이 의도대로 나오면 다음 단계 → [phase1-step2-idempotency.md](phase1-step2-idempotency.md)
