# Phase 1 - Step 3 참고: 왜 OpenFeign이 아니라 RestClient인가

> `phase1-step3-pg-call.md`에서 `RestClient`를 선택한 이유를 별도로 정리한 문서.
> 참고했던 `loopers-spring-kotlin-template`는 실제로 OpenFeign을 쓴다 — 그것과 다른 선택을 한 이유를 남긴다.

---

## 확인한 사실

- 이 Java 프로젝트(`external-api-training`)엔 Feign 관련 의존성이 전혀 없다.
- 참고한 Kotlin 템플릿(`loopers-spring-kotlin-template`)은 `FeignClient` 인터페이스(`PgSimulatorClient.kt`)로 PG 연동을 구현했다.

```kotlin
// Kotlin 템플릿의 방식 (OpenFeign)
@FeignClient(name = "pg-simulator", url = "\${pg.base-url}", configuration = [PgClientConfig::class])
interface PgSimulatorClient {
    @PostMapping("/api/v1/payments")
    fun requestPayment(@RequestHeader("X-USER-ID") userId: String, @RequestBody request: PgDto.PaymentRequest): ApiResponse<PgDto.PaymentResponse>
}
```

우리 프로젝트는 이 패턴을 그대로 따르지 않고 `RestClient`를 선택했다.

---

## RestClient를 선택한 이유

### 1. 이미 Spring Boot 표준에 포함되어 있다 (추가 의존성 불필요)

`RestClient`는 Spring Framework 6.1(Spring Boot 3.2+)부터 기본 제공된다. Feign은 `spring-cloud-openfeign` 별도 의존성과 `@EnableFeignClients` 설정이 필요하다. 프로젝트가 이미 `spring-cloud-dependencies` BOM을 쓰고 있어 추가하는 것 자체는 어렵지 않지만, "굳이 필요한가"의 문제였다.

### 2. 코드 흐름이 명시적으로 보인다 (선언적 vs 절차적)

```java
// Feign - 인터페이스 선언만 하면 프록시가 알아서 HTTP 호출을 생성
@PostMapping("/api/v1/payments")
fun requestPayment(...): ApiResponse<PgDto.PaymentResponse>

// RestClient - 실제로 뭘 하는지 코드에 다 드러남
restClient.post()
    .uri("/api/v1/transactions")
    .body(new PgTransactionRequest(amount))
    .retrieve()
    .body(PgTransactionResponse.class);
```

Feign은 "선언만 하면 마법처럼 동작"하는 방식이라 편하지만, 이 트레이닝처럼 **타임아웃, 재시도, Circuit Breaker를 세밀하게 제어하고 관찰하는 게 목적**일 땐 오히려 무엇이 일어나는지 코드에서 바로 보이는 `RestClient`가 학습에 유리하다.

### 3. Resilience4j 연동이 더 직접적이다

Step 4에서 `@CircuitBreaker`를 붙일 대상이 `RestClient`를 감싼 메서드다. Feign도 `@CircuitBreaker`를 붙일 수 있지만, Feign 자체가 이미 자체 fallback(`fallback = ...` 속성)이나 fault tolerance 설정을 갖고 있어서 두 메커니즘이 겹치면 어느 쪽이 실제로 동작하는지 헷갈리기 쉽다. `RestClient` + Resilience4j 조합이 "무슨 일이 일어나는지" 더 또렷하게 관찰된다.

### 4. Kotlin 템플릿을 그대로 답습하지 않고, 이 트레이닝 목적에 맞게 재판단했다

Kotlin 템플릿은 이미 완성된 프로덕션형 코드라 Feign처럼 보일러플레이트를 줄이는 선택이 합리적이다. 반면 이 트레이닝은 "Circuit Breaker 상태 전이를 직접 관찰하고 튜닝하는" 게 목표라, 개발 편의성보다 **가시성**을 우선한 선택이다.

---

## 결론

Feign이 틀린 선택은 아니고 실무에서도 흔히 쓰인다. 하지만 Phase 1의 학습 목표(Circuit Breaker/타임아웃/Fallback을 직접 눈으로 확인하고 튜닝하는 것)에는 호출 과정이 코드에 그대로 드러나는 `RestClient`가 더 맞는 도구라서 이걸 선택했다.

---

관련 문서: [phase1-step3-pg-call.md](phase1-step3-pg-call.md) · [phase1-step4-circuitbreaker.md](phase1-step4-circuitbreaker.md)
