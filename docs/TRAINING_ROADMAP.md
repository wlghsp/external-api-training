# 외부 API 연계와 실전 모니터링 — 트레이닝 로드맵

> 원본 계획: `plan.md` (같은 폴더, 모임/워크숍용으로 만든 4주 커리큘럼)
> 이 로드맵은 그 커리큘럼을 혼자 진행하기 위해 트레이닝 형식으로 재구성한 것이다.
> 실습 기반: `loop-pack-be-l2-vol2-java` fork 클론 (경로: `/Users/jihochoi/Documents/study/external-api-training`, 이 저장소 자체가 fork 루트)
> origin은 지호님 fork(wlghsp), upstream은 원본(Loopers-dev-lab)
> 이 저장소는 "루프팩 BE L2 Volume 2" 과정용 템플릿으로, main 브랜치 자체는 Example류 뼈대만 있는 백지 상태다 (커밋 히스토리에 다른 수강생들의 라운드별 실습 PR이 merge/revert되어 쌓여 있을 뿐, 현재 체크아웃 코드와는 무관)

문서를 만드는 게 목표가 아니다. **설명할 수 있는 수준**이 목표다.
각 Phase는 읽기 → 실습 → 이해도 테스트 → 블로그 발행 순서를 지킨다 (CLAUDE.md 트레이닝 규칙).

---

## 진행 원칙

```
실습 없이 다음 Phase로 넘어가지 않는다
문서 완성 → 블로그 발행 → 이해도 테스트 순서를 지킨다 (발행 전 테스트 금지)
"대충 알 것 같다"는 완료가 아니다 — 설명할 수 있어야 완료다
이해도 테스트를 통과하지 못하면 다음 Phase로 넘어가지 않는다 (각 phaseN-quiz.md 기준)
```

## 코드 작성 원칙 (모든 Phase 공통)

1. **객체지향** — Getter/Setter로 상태를 꺼내 바깥에서 판단하는 절차지향 방식(Anemic Domain Model)을 지양한다. Tell, Don't Ask — 객체에게 판단과 행위를 맡기고, 호출부는 지시만 한다. SOLID를 따른다.
2. **Java 최신 트렌드 반영** — 이 프로젝트는 Java 21을 타겟한다(`gradle.properties`). `record`, `sealed interface`, `switch` 패턴 매칭 등 Java 21까지 정식으로 들어온 기능을 적극 활용하고, 오래된 관용구(순수 enum + if-else 사슬, 원시 `Map<String,Object>` 등)에 안주하지 않는다. "Java를 알고 쓴다"는 게 목표 — 기능을 아는 것과 실제로 설계에 녹이는 것은 다르다.

---

## 실습 환경

loopers Java 템플릿은 멀티모듈 뼈대(apps/modules/supports)와 인프라·모니터링 docker-compose만 있는 백지 상태다.
Kotlin 버전과 달리 결제 도메인 코드와 PG 시뮬레이터가 없어서, 이번 트레이닝에서 직접 만들어야 한다.

- `apps/commerce-api` — 결제/멱등/서킷브레이커 등 이번 트레이닝의 비즈니스 로직을 붙일 메인 애플리케이션
- `apps/commerce-batch` — Phase 2 Reconciliation 잡을 붙일 배치 모듈
- `apps/commerce-streamer` — 이번 트레이닝에서는 사용 여부 미정
- `pg-simulator` — 아직 없음. Phase 1에서 외부 PG사 역할을 하는 간단한 Spring 앱으로 직접 만든다 (지연/실패 응답을 인위로 만들 수 있는 API 한두 개면 충분)
- `docker/infra-compose.yml` — MySQL, Redis (master+readonly). Kafka는 Phase 1~4 어디에도 필요하지 않아 주석 처리해둠 (`modules:kafka`, `apps:commerce-streamer` 코드/모듈은 그대로 있음 — 나중에 필요해지면 주석만 풀면 됨)
- `docker/monitoring-compose.yml` — Prometheus + Grafana (localhost:3000, admin/admin)

시작 전 최초 1회 세팅:
```shell
cd /Users/jihochoi/Documents/study/external-api-training
docker-compose -f ./docker/infra-compose.yml up -d
docker-compose -f ./docker/monitoring-compose.yml up -d
```

origin(wlghsp fork)에 자유롭게 커밋/푸시하면 된다. upstream과는 코드가 크게 갈라질 예정이라 리베이스나 PR은 고려하지 않는다.

---

## Phase 현황

- Phase 1 — 멱등 처리 · 서킷브레이커 · 폴백: 실습 ⬜ / 블로그 발행 ⬜ / 테스트 ⬜
- Phase 2 — 에러 처리 · 정합성 회복 (Reconciliation): 실습 ⬜ / 블로그 발행 ⬜ / 테스트 ⬜
- Phase 3 — 커넥션풀 산정 (Little's Law): 실습 ⬜ / 블로그 발행 ⬜ / 테스트 ⬜
- Phase 4 — Bulkhead 패턴 · 이중화: 실습 ⬜ / 블로그 발행 ⬜ / 테스트 ⬜

---

## 각 Phase 상세

### Phase 1: 외부 API는 언제든 실패한다 — 멱등 처리 · Circuit Breaker · Fallback
**파일**: `phase1-step1-pg-simulator.md` ~ `phase1-step5-fallback.md` (작성 완료)
**이해도 테스트**: `phase1-quiz.md` (grilling 스타일 인터뷰) — 통과 전까지 Phase 2 착수 금지
**완료 기준**:
- Idempotency Key를 클라이언트/게이트웨이/도메인 중 어디서 생성하고 어디까지 전파할지 설계 근거를 설명할 수 있다.
- DB unique constraint, Redis SETNX, Optimistic Lock 중 도메인 특성에 맞는 방식을 고르고 이유를 댈 수 있다.
- Resilience4j Circuit Breaker의 상태 전이(CLOSED → OPEN → HALF_OPEN)를 로그로 관찰하고 sliding window / failure rate threshold를 시나리오에 맞게 튜닝할 수 있다.
- Fallback이 "조용한 실패"가 되지 않도록 알람/메트릭과 함께 설계할 수 있다.

**실습**:
- `pg-simulator` 최초 구현: 지연/실패 응답을 파라미터나 랜덤 확률로 만들 수 있는 간단한 Spring 앱
- `apps/commerce-api`에서 `pg-simulator`를 호출하는 결제 요청 흐름 구현, 같은 요청을 두 번 보내도 중복 처리되지 않는 멱등 처리 구현
- Resilience4j Circuit Breaker 도입 후 `docker/monitoring-compose.yml`의 Grafana로 상태 전이 관찰

---

### Phase 2: 결제는 성공했는데 우리 DB는 모를 때 — 정합성 회복
**파일**: `phase2-reconciliation.md` (미작성)
**이해도 테스트**: `phase2-quiz.md` (미작성, Phase 1 quiz와 동일 형식) — 통과 전까지 Phase 3 착수 금지
**완료 기준**:
- 외부 API 호출과 로컬 트랜잭션의 경계(트랜잭션 안 vs 밖)를 어디에 그을지 결정하고 트레이드오프를 설명할 수 있다.
- PG timeout처럼 응답을 모르는 상태(unknown state)에서 결제 상태를 조회·동기화하는 Reconciliation 잡을 구현할 수 있다.
- 예외를 재시도 가능 / 재시도 불가능 / 비즈니스 / 시스템 네 축으로 분류하고 각각 다른 대응을 코드로 표현할 수 있다.
- 보상 트랜잭션(Compensating Transaction)이 필요한 시나리오와 단순 롤백으로 충분한 시나리오를 구분할 수 있다.
- 결제 상태가 확정/변경되는 시점(정상 승인, Reconciliation으로 unknown state 해소, 보상 트랜잭션 발동)에 도메인 이벤트를 발행하고, 이벤트 발행을 트랜잭션 경계와 어떻게 맞물리게 할지(트랜잭션 커밋 이후에만 발행 vs 함께 처리) 설계 근거를 설명할 수 있다.

**실습**:
- `pg-simulator`에서 응답 지연으로 timeout을 강제 발생시켜 unknown state 재현
- `apps/commerce-batch`를 활용해 Reconciliation 배치 잡 구현
- `PaymentModel`이 상태를 확정할 때(승인/실패/재조회로 확정) `PaymentCompletedEvent`/`PaymentFailedEvent` 같은 도메인 이벤트를 발행하도록 구현. Spring의 `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` 조합으로, 트랜잭션이 확실히 커밋된 후에만 이벤트가 리스너에 전달되게 한다 (커밋 전에 이벤트가 나가면 리스너가 아직 존재하지 않는 데이터를 참조하게 되는 문제를 피하기 위함)

---

### Phase 3: 풀 사이즈를 '감'이 아닌 '계산'으로 산정한다
**파일**: `phase3-connection-pool-sizing.md` (미작성)
**이해도 테스트**: `phase3-quiz.md` (미작성, Phase 1 quiz와 동일 형식) — 통과 전까지 Phase 4 착수 금지
**완료 기준**:
- Little's Law를 TPS와 평균 latency에 대입해 필요 커넥션 수를 계산할 수 있다.
- HikariCP 핵심 파라미터의 의미와 잘못 설정했을 때의 증상을 매칭할 수 있다.
- HttpClient 커넥션풀이 DB 커넥션풀과 어떻게 상호작용하는지 설명할 수 있다.
- 부하 테스트로 산정값을 검증하고 p99 latency와 풀 사용률 그래프로 결과를 해석할 수 있다.

**실습**:
- `apps/commerce-api`의 HikariCP 설정을 의도적으로 과소/과다 설정해 증상 재현
- 부하 테스트 도구(k6 등, 미정)로 TPS 측정 → Little's Law로 계산한 값과 비교
- Grafana에서 p99 latency, 커넥션풀 사용률 그래프 확인

---

### Phase 4: 한 PG사가 죽어도 우리 서비스는 죽지 않는다
**파일**: `phase4-bulkhead-ha.md` (미작성)
**이해도 테스트**: `phase4-quiz.md` (미작성, Phase 1 quiz와 동일 형식) — 전체 트레이닝 완료 조건
**완료 기준**:
- Bulkhead 패턴을 Semaphore 방식과 ThreadPool 방식으로 각각 구현하고 격리 수준·비용을 비교 설명할 수 있다.
- 외부 의존성 하나의 장애가 전체 스레드를 잠식하는 시나리오를 재현하고 Bulkhead로 막는 과정을 코드와 메트릭으로 보여줄 수 있다.
- 결제 PG 이중화 전략을 비용/정합성/운영 복잡도 관점에서 비교해 의사결정할 수 있다.
- Phase 1~3의 설계가 이중화 구조 위에서 어떻게 함께 동작하는지 전체 그림으로 설명할 수 있다.

**실습**:
- `pg-simulator`를 느리게 응답하도록 만들어 스레드 잠식 시나리오 재현 (Bulkhead 적용 전/후 비교)
- Resilience4j Bulkhead(Semaphore/ThreadPool) 각각 적용해 메트릭 비교
- (선택) 두 번째 `pg-simulator` 인스턴스를 띄워 PG 이중화 시나리오 설계

---

## 지금 당장 할 것

**Phase 1 문서 작성** → `phase1-idempotency-circuitbreaker.md`
- docker-compose 두 개 up (infra, monitoring)
- `pg-simulator` 모듈 뼈대부터 직접 설계 (지연/실패를 어떻게 흉내낼지 결정)
- Resilience4j 공식 문서에서 Circuit Breaker 개념 훑기
- Claude와 함께 phase1 문서 작성 → 실습 → 블로그 발행 → 테스트
