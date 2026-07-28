# 템플릿 초기 상태

> `loopers-java-spring-template` fork를 clone한 직후, 아무 실습 코드도 추가하지 않은 시점의 구조 기록.
> Phase별 실습을 시작하기 전에 "원래 뭐가 있었는지" 참고용.

---

## 모듈 구성 (`settings.gradle.kts`)

```
rootProject.name = "loopers-java-spring-template"

apps/commerce-api        # 메인 애플리케이션 (REST API)
apps/commerce-streamer   # Kafka Consumer 애플리케이션
apps/commerce-batch      # Spring Batch 애플리케이션
modules/jpa               # JPA/DataSource/QueryDSL 공통 설정
modules/redis              # Redis 공통 설정
modules/kafka              # Kafka 공통 설정
supports/jackson           # Jackson 직렬화 설정
supports/logging           # Logback + Slack 알람 연동 설정
supports/monitoring        # Prometheus/Micrometer 설정
```

`pg-simulator`는 없음 — Phase 1에서 직접 추가해야 하는 모듈.

Java 21, Spring Boot(버전은 gradle.properties), Gradle Kotlin DSL 멀티모듈. 루트 `build.gradle.kts`의 `subprojects` 블록에서 모든 모듈에 공통으로 Lombok, Validation, Jackson JSR310, Jacoco, Spring Cloud BOM을 적용.

---

## apps/commerce-api

REST API 서버. `Example` 도메인 하나만 뼈대로 존재하며, 레이어드 아키텍처 패턴을 보여주는 용도.

```
com.loopers
├── CommerceApiApplication.java
├── application/example/
│   ├── ExampleFacade.java        # Facade: 여러 도메인 서비스 orchestration 지점
│   └── ExampleInfo.java          # 도메인 → API 응답용 DTO 변환 지점
├── domain/example/
│   ├── ExampleModel.java         # JPA 엔티티 (BaseEntity 상속)
│   ├── ExampleRepository.java    # 도메인 레이어의 리포지토리 인터페이스
│   └── ExampleService.java       # 도메인 로직
├── infrastructure/example/
│   ├── ExampleJpaRepository.java     # Spring Data JPA 리포지토리
│   └── ExampleRepositoryImpl.java    # ExampleRepository 구현체 (JPA 위임)
├── interfaces/api/
│   ├── ApiControllerAdvice.java  # 전역 예외 처리
│   ├── ApiResponse.java          # 공통 응답 래퍼
│   └── example/
│       ├── ExampleV1ApiSpec.java     # Swagger 스펙 인터페이스
│       ├── ExampleV1Controller.java  # 컨트롤러
│       └── ExampleV1Dto.java         # 요청/응답 DTO
└── support/error/
    ├── CoreException.java        # 커스텀 예외 베이스
    └── ErrorType.java            # 에러 코드 enum (INTERNAL_ERROR, BAD_REQUEST, NOT_FOUND, CONFLICT)
```

레이어 흐름: `interfaces.api` → `application`(Facade) → `domain`(Service/Repository 인터페이스) → `infrastructure`(Repository 구현).

**테스트**: `CommerceApiContextTest`(컨텍스트 로딩), `ExampleModelTest`(단위), `ExampleServiceIntegrationTest`(통합, Testcontainers), `ExampleV1ApiE2ETest`(E2E), `CoreExceptionTest`.

**설정** (`application.yml`): Tomcat 스레드풀(max 200), graceful shutdown, profile은 `local/test/dev/qa/prd` 5종. `jpa.yml`/`redis.yml`/`logging.yml`/`monitoring.yml`을 import해서 조합.

결제/멱등/서킷브레이커 관련 코드는 전혀 없음 — Phase 1~4에서 채워야 할 대상.

---

## apps/commerce-batch

Spring Batch 애플리케이션. `demo`라는 이름의 예시 Job 하나만 존재.

```
com.loopers.batch
├── job/demo/
│   ├── DemoJobConfig.java
│   └── step/DemoTasklet.java
└── listener/
    ├── ChunkListener.java
    ├── JobListener.java
    └── StepMonitorListener.java   # Step 실행 모니터링 (Micrometer 연동 추정)
```

Reconciliation 잡(Phase 2 대상)은 없음. `DemoJobE2ETest`만 존재.

---

## apps/commerce-streamer

Kafka Consumer 애플리케이션. `DemoKafkaConsumer` 하나만 존재하는 뼈대. 이번 트레이닝에서 사용 여부 미정 (로드맵 참고).

---

## modules — 인프라 공통 설정

- **jpa**: `DataSourceConfig`, `JpaConfig`, `QueryDslConfig`, `BaseEntity`(공통 엔티티 베이스). `testFixtures`에 MySQL Testcontainers 설정과 `DatabaseCleanUp` 유틸 포함 — 통합 테스트에서 재사용.
- **redis**: `RedisConfig`, `RedisNodeInfo`, `RedisProperties`. `testFixtures`에 Redis Testcontainers 설정과 `RedisCleanUp` 유틸.
- **kafka**: `KafkaConfig` 하나.

세 모듈 모두 `*.yml` 설정 파일을 갖고 있고, `apps/*`의 `application.yml`에서 `spring.config.import`로 가져다 씀.

---

## supports — 횡단 관심사

- **jackson**: `JacksonConfig` (직렬화 공통 설정).
- **logging**: Logback 설정 + Slack 알람 연동(`slack-appender.xml`, 환경별 `slack-log-{dev,qa,prd}.xml`). JSON/plain 콘솔 appender 분리.
- **monitoring**: `monitoring.yml` (Prometheus/Micrometer 관련으로 추정, 소스 코드는 아직 없고 설정 파일만 존재).

---

## docker

- `infra-compose.yml` — MySQL, Redis, Kafka
- `monitoring-compose.yml` — Prometheus + Grafana (localhost:3000, admin/admin)
- `grafana/` — 대시보드 프로비저닝 관련 디렉토리 (내용 미확인)

---

## 공통 패턴 요약

- **레이어드 아키텍처**: `interfaces → application → domain → infrastructure`, `Example` 도메인이 이 패턴의 레퍼런스 구현체.
- **예외 처리**: `CoreException` + `ErrorType` enum + `ApiControllerAdvice` 전역 처리 조합. 새 에러 타입 추가 시 `ErrorType`에 enum 상수 추가하는 패턴을 따르면 됨.
- **테스트 계층**: 단위(Model) / 통합(Service, Testcontainers) / E2E(Controller) 3단 구조.
- **설정 분리**: 모듈별 `*.yml`을 앱의 `application.yml`이 import, profile은 `local/test/dev/qa/prd` 5종 공통.

Phase 1~4에서 만들 것: `pg-simulator` 모듈(없음), 결제 도메인 코드(없음), Resilience4j 연동(없음), Reconciliation 배치 잡(없음), Bulkhead 설정(없음). 이 문서에 적힌 것 외에는 전부 실습으로 채워야 하는 백지 상태.
