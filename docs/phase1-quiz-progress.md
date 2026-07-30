# Phase 1 이해도 테스트 — 진행 기록

> `phase1-quiz.md` 질문 뱅크 기준 grilling 세션 진행 상황.
> 다시 시작할 때 "phase1 계속하자"라고 하면 이 파일 기준으로 이어서 진행.

## 진행 상황

- [x] 축 1: Idempotency Key 설계 (5문항 완료)
- [x] 축 2: 멱등 저장 방식 선택 (8문항 완료)
- [x] 축 2-1: 객체지향 설계 · Java 21 활용 (5문항 완료)
- [ ] 축 3: Circuit Breaker 상태 전이 — **질문 1부터 재개**
- [ ] 축 4: Fallback 설계 (미시작)
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
