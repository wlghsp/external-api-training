# Phase 1 이해도 테스트 — 질문 뱅크

> 구현이 끝난 뒤 "phase1 테스트해줘" 라고 하면, 이 파일을 기반으로 grilling 스타일(한 번에 하나씩, 답변 받고 피드백 후 다음 질문)로 진행한다.
> 정답을 알려주는 게 목적이 아니라, `TRAINING_ROADMAP.md`의 완료 기준을 스스로 설명할 수 있는지 확인하는 게 목적이다.
> 완료 기준 4개 축 × 여러 질문. 답변에 따라 꼬리 질문으로 더 파고든다.

---

## 진행 방식 (테스트 시 따를 것)

1. 아래 질문을 순서대로, **하나씩만** 던진다. 여러 개를 한 번에 묻지 않는다.
2. 사용자가 답하면 맞았는지/부족한지 짧게 피드백하고, 부족하면 그 자리에서 힌트를 주거나 재질문한다. 정답을 바로 알려주지 않는다.
3. 답변이 표면적이면 "왜?"로 한 단계 더 파고든다 (예: "DB unique constraint를 선택했다" → "동시에 같은 요청이 두 번 오면 그 constraint가 정확히 무슨 일을 하나?")
4. 코드/실습 내용과 연결지어 묻는다 — 추상적 정의 암기가 아니라 본인이 짠 코드 기준으로 답하게 유도
5. 4개 축을 다 마치면 종합 질문으로 마무리 (맨 아래 참고)

---

## 축 1: Idempotency Key 설계

1. Idempotency Key는 클라이언트/게이트웨이/도메인 중 어디서 생성해야 한다고 판단했나? 왜?
2. 서버(도메인 레이어)가 키를 생성하는 방식을 쓰면 어떤 문제가 생기나? (재시도 시나리오로 설명 유도)
3. 이 키는 요청의 어디에 담겨서 전달되나 (헤더 vs 바디)? 그렇게 정한 이유는?
4. 같은 Idempotency Key로 **금액이 다른** 두 번째 요청이 오면 어떻게 처리해야 하나? (실제 코드가 이걸 처리하는지 확인)
5. Idempotency Key의 생명주기는 어떻게 되나 — 영구 보관인가, TTL이 있나? 결제 도메인에서 이게 왜 중요한가?

## 축 2: 멱등 저장 방식 선택

1. DB unique constraint / Redis SETNX / Optimistic Lock 각각의 특성과 트레이드오프를 설명해봐라. Optimistic Lock은 왜 이 문제(최초 요청 여부 판단)에 맞지 않았나?
2. 최종적으로 Redis SETNX(1차) + DB unique constraint(최종 안전망) 이중 구조로 갔는데, DB unique constraint 하나만 썼을 때와 비교해 무엇이 나아지나?
3. 반대로 Redis SETNX 하나만 썼다면 어떤 리스크가 남는가? 그 리스크를 DB unique constraint가 어떻게 메꿔주나?
4. "동시에 같은 키로 두 요청이 들어오는" 경쟁 상황이 실제로 어떻게 처리되나 — Redis 레벨과 DB 레벨 각각에서 코드 상 어느 라인이 그 경쟁을 해소하나?
5. `IdempotencyLock.acquire()`가 실패했는데 DB에는 아직 해당 레코드가 없는 상황이 있을 수 있나? 있다면 언제, 그리고 지금 코드는 이 상황을 어떻게 처리하나?
6. `PaymentService.createNew()`에서 `DataIntegrityViolationException`을 잡는 코드가 여전히 필요한 이유는? Redis가 1차로 막아주는데 왜 DB에서도 또 막아야 하나 — 어떤 상황이면 Redis 선점이 뚫리는지 구체적으로 설명해봐라.
7. Redis 락의 TTL을 5분으로 잡았다. 이 값을 결정할 때 고려해야 할 기준은 무엇인가 (너무 짧으면/너무 길면 각각 무슨 문제가 생기나)?
8. 지금 구현은 명시적 `release()`를 호출하지 않는다. 이게 문제가 되는 시나리오가 있다면 무엇인가?

## 축 2-1: 객체지향 설계 · Java 21 활용 (PaymentModel/PaymentStatus)

1. `PaymentModel`에 `@Getter`를 붙이지 않았다. `status`를 왜 밖으로 그냥 꺼내주지 않고 `approveOrElse(...)` 같은 행위 메서드로 감쌌나? (Tell, Don't Ask를 본인 말로 설명)
2. `PaymentStatus`를 enum이 아니라 sealed interface(`Pending`/`Approved`/`Failed` record)로 설계했다. enum으로 했다면 어떤 걸 놓쳤을까 — 특히 `transactionKey`가 "APPROVED일 때만 존재한다"는 규칙을 enum은 어떻게 표현하고 있었나(직전 버전을 떠올려서 비교)?
3. `PaymentModel`은 `PaymentStatusType`(순수 enum, DB 컬럼용)과 `PaymentStatus`(sealed interface, 도메인 로직용) 두 가지 상태 표현을 갖고 있다. 왜 하나로 통일하지 않았나 — JPA 쪽 제약이 무엇이었나?
4. `switch (status())`로 상태별 분기를 할 때, 세 가지 케이스(Pending/Approved/Failed) 중 하나를 처리 안 하고 빠뜨리면 무슨 일이 일어나나? enum + 일반 `switch`/`if-else`였다면 같은 실수가 어떻게 드러났을까 — 이 차이가 왜 중요한가?
5. `approveOrElse`가 `PgApproveResult`를 받기 위해 `Supplier<PgApproveResult>` 콜백을 매개변수로 받는다. 왜 `PgSimulatorClient`를 직접 필드로 주입받지 않았나?

## 축 3: Circuit Breaker 상태 전이

1. CLOSED → OPEN → HALF_OPEN, 각 상태에서 요청이 실제로 어떻게 처리되나?
2. `sliding-window-size`와 `minimum-number-of-calls`는 각각 무슨 역할을 하나? 왜 별개의 값으로 존재하나?
3. `failure-rate-threshold`를 50%로 설정했다면, 왜 그 값인가? 더 낮추거나 높이면 어떤 트레이드오프가 생기나?
4. `slow-call-duration-threshold`는 왜 필요한가 — 그냥 실패(exception)만 세면 안 되는 이유는?
5. OPEN 상태에서 pg-simulator로 실제 HTTP 요청이 나가는가, 안 나가는가? 그걸 어떻게 확인했나 (로그/Grafana)?
6. HALF_OPEN에서 CLOSED로 돌아오려면 정확히 어떤 조건이 충족돼야 하나?
7. Grafana에서 실제로 관찰한 상태 전이 그래프를 설명해봐라 — 언제 OPEN이 됐고 언제 복구됐나?

## 축 4: Fallback 설계

1. Fallback이 실행되면 사용자에게 어떤 응답이 가나 (상태 코드, 메시지)? 200을 반환하지 않는 이유는?
2. Fallback이 "조용한 실패"가 되지 않으려면 최소한 뭐가 있어야 한다고 판단했나?
3. 실제로 추가한 메트릭/로그는 무엇이고, Grafana에서 어떻게 확인할 수 있나?
4. Fallback 메서드의 시그니처는 왜 원본 메서드와 파라미터가 같고 `Throwable`이 추가로 붙어야 하나?
5. 만약 Fallback에서도 실패하면 (예: 메트릭 등록 실패) 무슨 일이 생기나? 이건 고려했나?

---

## 종합 질문 (4개 축 완료 후)

1. Phase 1에서 구현한 멱등 처리와 Circuit Breaker는 서로 어떤 순서로 실행되나 — 멱등 체크가 먼저인가, PG 호출(CircuitBreaker로 감싼)이 먼저인가? 그 순서가 왜 중요한가?
2. 만약 Idempotency Key 저장(DB 기록)은 성공했는데 그 직후 PG 호출이 Circuit Breaker에 의해 즉시 fallback 처리됐다면, 이 결제 건의 상태는 어떻게 되나? 사용자가 재시도하면 무슨 일이 일어나나? (이 질문은 Phase 2의 unknown state와 연결되는 지점 — 답이 막히면 정상)
