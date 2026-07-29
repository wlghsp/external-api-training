# Phase 1 - Step 1: pg-simulator 모듈 뼈대

> 외부 PG사 역할을 하는 간단한 Spring 앱. 지연/실패를 인위로 만들 수 있어야 한다.
> 이 문서는 예시 코드를 담고 있다 — 그대로 베끼지 말고, 결정 지점을 먼저 스스로 판단한 뒤 참고용으로만 본다.

---

## 결정 지점

| 항목 | 선택 | 근거 |
|---|---|---|
| 지연 흉내 | 요청 파라미터(`delayMs`) | 고정 sleep보다 재현성 좋음 |
| 실패 흉내 | 파라미터 강제(`forceFail`) + 확률(`failureRate`) 둘 다 지원 | 강제 지정은 디버깅용, 확률은 step 4 sliding window 관찰용 |
| 포트 | `8082` | commerce-api 앱 자체(8080)와는 안 겹치지만, `monitoring.yml`의 actuator 관리 포트(`management.server.port: 8081`)와 처음에 충돌해서 8082로 조정. docker-compose 포트(3000/3306/6379/6380/9090)와도 무관 |

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
  port: 8082

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
curl -X POST http://localhost:8082/api/v1/transactions -H "Content-Type: application/json" -d '{}'

# 지연 재현
curl -X POST "http://localhost:8082/api/v1/transactions?delayMs=3000" -H "Content-Type: application/json" -d '{}'

# 강제 실패
curl -X POST "http://localhost:8082/api/v1/transactions?forceFail=true" -H "Content-Type: application/json" -d '{}'
```

지연/실패 응답이 의도대로 나오면 다음 단계 → [phase1-step2-idempotency.md](phase1-step2-idempotency.md)
