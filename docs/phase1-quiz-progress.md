# Phase 1 이해도 테스트 — 진행 기록

> `phase1-quiz.md` 질문 뱅크 기준 grilling 세션 진행 상황.
> 다시 시작할 때 "phase1 계속하자"라고 하면 이 파일 기준으로 이어서 진행.

## 진행 상황

- [x] 축 1: Idempotency Key 설계 (5문항 완료)
- [x] 축 2: 멱등 저장 방식 선택 (8문항 완료)
- [x] 축 2-1: 객체지향 설계 · Java 21 활용 (5문항 완료)
- [x] 축 3: Circuit Breaker 상태 전이 (7문항 완료)
- [ ] 축 4: Fallback 설계 — **질문 3부터 재개** (질문 1, 2 완료)
- [ ] 종합 질문 (미시작)

---

## 발견된 개선 포인트 (코드에 아직 반영 안 됨)

1. **같은 idempotencyKey + 다른 amount 처리 누락**
   - 파일: `PaymentService.getOrCreate` / `createNew`
   - 현재: amount 비교 없이 기존 레코드를 그대로 반환 (조용히 삼킴)
   - 개선 방향: Stripe처럼 파라미터 불일치 시 409 Conflict로 명시적 거부

2. **`release()` 미호출**
   - 파일: `IdempotencyLock.java` (release 메서드는 존재하나 `PaymentService`에서 호출 안 함)
   - 현재: 결제가 실패로 끝나도 Redis 락이 TTL(5분)까지 유지됨 → 정당한 재시도가 불필요하게 지연
   - 개선 방향: 실패 처리 시점에 명시적 release, 성공 시엔 DB가 이미 있으므로 선택적

---

## 이해가 더 필요하다고 표시된 개념

- **sealed interface + record (Java 21)**: `PaymentStatus`가 왜 enum이 아니라 sealed interface(Pending/Approved/Failed record)로 설계됐는지, "상태별로 다른 데이터를 강제"하는 패턴이 처음 접해본 문법이라 추가 학습 필요.
  - 참고 코드: `PaymentModel.status()` (50-56라인), `PaymentModel.approveOrElse()` (62-68라인)
  - 다음에 시간 날 때: Java 21 sealed classes / pattern matching switch 공식 문서 훑어보기, 간단한 실습으로 enum vs sealed interface 버전 비교해보기

## 축 4 진행 중 (Fallback 설계)

- 질문 1 (fallback 응답: 상태코드/메시지, 왜 200 아닌지) — 완료. 500 + "결제 서비스가 일시적으로 불안정합니다" 메시지 (`PgSimulatorClient.approveFallback`, `ErrorType.INTERNAL_ERROR`). 200을 주면 클라이언트가 성공으로 오해해 재시도를 안 하거나 후속 처리를 진행해버릴 위험.
- 질문 2 (조용한 실패 방지에 최소 필요한 것) — 완료. 로그(개별 사건의 원인 추적) + 메트릭(`pg_client.fallback` Counter, 추이/이상 감지)의 역할 구분까지 정확히 답변.
- **질문 3부터 재개**: 실제로 추가한 메트릭/로그가 무엇이고 Grafana에서 어떻게 확인하는지 (`fallbackCounter`, 태그 `target=pg-simulator` 기준으로 답변 유도할 것)

---

## 축 1~2-1 요약 (본인이 정확히 답한 핵심)

- Idempotency Key는 **클라이언트**가 생성 (재시도 주체가 클라이언트이므로 서버 생성은 무의미)
- 키는 **HTTP 헤더**(`Idempotency-Key`)로 전달 — 바디는 비즈니스 데이터, 헤더는 전송 계층 메타데이터라는 구분
- Optimistic Lock은 "이미 존재하는 row의 업데이트 충돌 감지"용 — 최초 생성 경쟁에는 애초에 적용 불가
- Redis SETNX(1차, 빠름) + DB unique constraint(최종 안전망, 영구) 이중 구조
  - Redis 장애/TTL 만료 시에도 DB가 최종적으로 정합성 보장
  - 경쟁 해소 지점: `IdempotencyLock.acquire()`의 `setIfAbsent` (Redis 레벨), `PaymentService.createNew()`의 `DataIntegrityViolationException` 캐치 (DB 레벨)
- Tell, Don't Ask: `approveOrElse`로 상태 판단 로직을 `PaymentModel` 내부로 캡슐화 → 상태 전이 규칙 수정 지점을 하나로 고정
- sealed interface의 exhaustive switch → 케이스 누락을 컴파일 타임에 발견 (enum + if-else는 런타임까지 발견 못 함)
- `Supplier<PgApproveResult>` 콜백 → PG 호출의 지연 평가, `PaymentModel`이 인프라 의존성을 직접 갖지 않게 함

## 축 3 요약 (Circuit Breaker)

- OPEN 상태에서는 실제 PG 호출이 나가지 않고 fallback이 즉시 실행됨 (스레드/커넥션 낭비 방지)
- `minimum-number-of-calls`(5)는 표본이 부족할 때 성급한 OPEN 전환을 막는 안전장치, `sliding-window-size`(10)는 실패율 계산 기준 윈도우
- `failure-rate-threshold`(50%) 트레이드오프: 너무 낮추면 정상 변동성에도 과잉 차단(false positive), 너무 높이면 죽어가는 의존성에 계속 요청을 쏟아부음
- `slow-call-duration-threshold`(2s)가 필요한 이유: 예외 없이 응답만 느린 호출은 실패로 안 잡혀서 실패율 계산에서 누락됨 → 스레드/커넥션이 계속 묶여 있다가 결국 시스템 전체 장애로 번짐
- HALF_OPEN → CLOSED 조건: 시험 호출 3번 "모두 성공"이 아니라, CLOSED 때와 동일한 실패율 threshold(50%)를 재사용 — 50% 안 넘으면 CLOSED, 넘으면 다시 OPEN
- 실습 관찰: 15번 실패 호출 시 `minimum-number-of-calls`(5) 넘긴 시점부터 실패율 계산 → 50% 초과로 OPEN, `wait-duration-in-open-state`(10s) 후 자동 HALF_OPEN 전환 확인
