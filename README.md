# 외부 API 연계와 실전 모니터링 — 트레이닝

Loopers Java 템플릿(멀티모듈 뼈대 + 인프라/모니터링 docker-compose)을 기반으로, 외부 API(PG) 연계 시 마주치는 실전 문제들을 직접 구현하며 익히는 트레이닝 저장소입니다.

문서를 만드는 게 목표가 아니라 **설명할 수 있는 수준**이 목표입니다. 각 Phase는 읽기 → 실습 → 이해도 테스트 → 블로그 발행 순서를 지킵니다. 전체 계획은 [docs/TRAINING_ROADMAP.md](docs/TRAINING_ROADMAP.md) 참고.

## 코드 작성 원칙

1. **객체지향** — Getter/Setter로 상태를 꺼내 바깥에서 판단하는 절차지향 방식을 지양합니다. Tell, Don't Ask — 객체에게 판단과 행위를 맡기고 SOLID를 따릅니다.
2. **Java 최신 트렌드 반영** — Java 21을 타겟으로, `record`, `sealed interface`, `switch` 패턴 매칭 등 정식으로 들어온 기능을 적극 활용합니다.

## Phase 진행 현황

| Phase | 주제 | 문서 | 상태 |
|---|---|---|---|
| 1 | 멱등 처리 · Circuit Breaker · Fallback | [phase1-step1~5](docs/) · [phase1-quiz.md](docs/phase1-quiz.md) | 진행 중 |
| 2 | 에러 처리 · 정합성 회복 (Reconciliation) | `phase2-reconciliation.md` (예정) | 예정 |
| 3 | 커넥션풀 산정 (Little's Law) | `phase3-connection-pool-sizing.md` (예정) | 예정 |
| 4 | Bulkhead 패턴 · 이중화 | `phase4-bulkhead-ha.md` (예정) | 예정 |

### Phase 1: 외부 API는 언제든 실패한다 — 멱등 처리 · Circuit Breaker · Fallback

- [phase1-step1-pg-simulator.md](docs/phase1-step1-pg-simulator.md) — 지연/실패를 재현 가능한 pg-simulator 모듈
- [phase1-step2-idempotency.md](docs/phase1-step2-idempotency.md) — Idempotency Key, Redis SETNX(1차) + DB unique constraint(최종 안전망) 이중 구조
- [phase1-step3-pg-call.md](docs/phase1-step3-pg-call.md) — commerce-api → pg-simulator 호출 흐름
- [phase1-step4-circuitbreaker.md](docs/phase1-step4-circuitbreaker.md) — Resilience4j Circuit Breaker, 상태 전이 관찰
- [phase1-step5-fallback.md](docs/phase1-step5-fallback.md) — 조용한 실패가 되지 않는 Fallback 설계
- [phase1-quiz.md](docs/phase1-quiz.md) — 이해도 테스트 질문 뱅크 (구현 완료 후 이 기준으로 grilling 스타일 인터뷰 진행, 통과해야 Phase 2 착수)

이 저장소의 초기 상태(무엇이 원래 있었고 무엇을 직접 채워야 하는지)는 [docs/INITIAL_STATE.md](docs/INITIAL_STATE.md) 참고.

---

## Getting Started
현재 프로젝트 안정성 및 유지보수성 등을 위해 아래와 같은 장치를 운용하고 있습니다. 이에 아래 명령어를 통해 프로젝트의 기반을 설치해주세요.
### Environment
`local` 프로필로 동작할 수 있도록, 필요 인프라를 `docker-compose` 로 제공합니다.
```shell
docker-compose -f ./docker/infra-compose.yml up
```
### Monitoring
`local` 환경에서 모니터링을 할 수 있도록, `docker-compose` 를 통해 `prometheus` 와 `grafana` 를 제공합니다.

애플리케이션 실행 이후, **http://localhost:3000** 로 접속해, admin/admin 계정으로 로그인하여 확인하실 수 있습니다.
```shell
docker-compose -f ./docker/monitoring-compose.yml up
```

## About Multi-Module Project
본 프로젝트는 멀티 모듈 프로젝트로 구성되어 있습니다. 각 모듈의 위계 및 역할을 분명히 하고, 아래와 같은 규칙을 적용합니다.

- apps : 각 모듈은 실행가능한 **SpringBootApplication** 을 의미합니다.
- modules : 특정 구현이나 도메인에 의존적이지 않고, reusable 한 configuration 을 원칙으로 합니다.
- supports : logging, monitoring 과 같이 부가적인 기능을 지원하는 add-on 모듈입니다.

```
Root
├── apps ( spring-applications )
│   ├── 📦 commerce-api
│   ├── 📦 commerce-batch
│   ├── 📦 commerce-streamer
│   └── 📦 pg-simulator   (트레이닝 Phase 1에서 추가)
├── modules ( reusable-configurations )
│   ├── 📦 jpa
│   ├── 📦 redis
│   └── 📦 kafka
└── supports ( add-ons )
    ├── 📦 jackson
    ├── 📦 monitoring
    └── 📦 logging
```
