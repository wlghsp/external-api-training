# Grafana 대시보드 세팅 가이드

> 이 트레이닝 전체 Phase에서 공통으로 쓰는 Grafana 세팅 절차 — 한 번만 세팅해두고 Phase마다 패널만 추가한다.
> Phase 1: Circuit Breaker 상태 관찰, Fallback 메트릭. Phase 3: p99 latency, 커넥션풀 사용률. Phase 4: Bulkhead 메트릭.

---

## 0. 기동

```shell
docker-compose -f ./docker/monitoring-compose.yml up -d
```

Prometheus: `localhost:9090`, Grafana: `localhost:3000` (admin/admin).

---

## 1. 데이터소스 확인 (이미 자동 구성됨 — 새로 만들 필요 없음)

`docker/grafana/provisioning/datasources/datasource.yml`이 Prometheus 데이터소스를 자동 등록한다. Grafana 접속 후 확인만 하면 된다:

좌측 메뉴 → **Connections → Data sources** → "Prometheus"가 이미 등록되어 있는지 확인.

---

## 2. Prometheus가 commerce-api를 실제로 스크래핑하는지 확인

`localhost:9090/targets` 접속 → `spring-boot-app` job의 상태가 **UP**인지 확인.

**주의**: `/actuator/prometheus`는 애플리케이션 포트(8080)가 아니라 **actuator 관리 포트(8081)**에 있다. `supports/monitoring`의 `monitoring.yml`이 `management.server.port: 8081`로 actuator 엔드포인트를 별도 내장 톰캣에 분리해뒀기 때문 — 8080으로 curl하면 404가 난다.

DOWN(또는 404)이면:
- commerce-api가 떠 있는지 확인
- `docker/grafana/prometheus.yml`의 타겟이 `host.docker.internal:8081`인지 확인 (8080이 아님. Docker 컨테이너 안에서 호스트의 localhost는 `host.docker.internal`로 접근해야 함)

---

## 3. 대시보드 생성

1. 좌측 메뉴 → **Dashboards** → 우측 상단 **New** → **New Dashboard**
2. **Add visualization** 클릭
3. 데이터소스로 **Prometheus** 선택

---

## 4. Phase 1에서 추가할 패널

### 4-1. Circuit Breaker 상태 패널

**메트릭 성격**: `resilience4j_circuitbreaker_state`는 게이지(gauge) — 상태마다 별도 시계열로 나뉘고, 값은 현재 그 상태인지(1) 아닌지(0)를 나타낸다. `state` 라벨 값은 **소문자**로 `closed`/`open`/`half_open`/`disabled`/`forced_open`/`metrics_only` 6가지가 각각 별도 라인이 된다 (실제 쿼리 결과로 확인함 — Resilience4j 문서/로그에 나오는 `CLOSED`/`OPEN` 대문자 표기와 다르니 쿼리 시 소문자로 입력). 코드 추가 불필요 — Resilience4j가 자동 노출한다 (step 4에서 확인 가능).

**입력 절차**:
1. **New Dashboard → Add visualization → Prometheus** 선택
2. 쿼리 입력창(우측 "Metric" 드롭다운 또는 하단 코드 에디터)에 다음을 입력:
   ```
   resilience4j_circuitbreaker_state{name="pg-simulator"}
   ```
   `name="pg-simulator"` 라벨을 안 붙이면 다른 CircuitBreaker 인스턴스가 있을 때 같이 섞여 나온다 — 지금은 하나뿐이라 상관없지만 습관으로 붙여두는 게 좋다.
3. **Run queries** 클릭 → 우측 상단 시각화 타입을 **Time series**(기본값)로 두면 `state`별 라인 6개가 보인다 (평소엔 `closed=1`, 나머지=0)
4. **읽는 법**: 특정 시점에 `state="open"` 라인이 1로 올라가고 `state="closed"`가 0으로 내려가면 그 시점에 OPEN 전이가 일어난 것. 라인이 여러 개(6개)라 처음엔 복잡해 보이는데, `disabled`/`forced_open`/`metrics_only`는 이 트레이닝에서 안 쓰는 상태라 항상 0으로 깔려있는 게 정상이다.
5. 우측 패널 옵션에서 **Panel options → Title**을 "Circuit Breaker State"로 변경
6. 우측 상단 **Save dashboard** (대시보드 이름을 예: "Phase 1 - PG 연동"으로 지정)

**확인 팁**: step 4의 강제 실패 반복 요청을 보내는 동안 이 패널을 열어두면, `sliding-window-size`(10건)가 채워지고 `failure-rate-threshold`(50%)를 넘는 순간 실시간으로 라인이 바뀌는 걸 볼 수 있다.

### 4-2. Fallback 발생 횟수 패널

**메트릭 성격**: `pg_client_fallback_total`은 카운터(counter) — 계속 누적되기만 하고 절대 줄지 않는다. step 5에서 `PgSimulatorClient`에 `Counter` 코드를 추가해야 이 메트릭 자체가 생긴다 (그 전엔 Prometheus에 이름 자체가 없어서 쿼리해도 빈 결과).

**함정**: 카운터를 그냥 쿼리하면(`pg_client_fallback_total`) 계단식으로 계속 우상향하는 그래프만 나와서 "언제 얼마나 자주 발생했는지"가 안 보인다. **`rate()`를 씌워야** "초당 발생 빈도" 형태로 의미 있게 보인다.

**입력 절차**:
1. 같은 대시보드에서 **Add → Visualization**으로 패널 추가
2. 쿼리:
   ```
   rate(pg_client_fallback_total[1m])
   ```
   `[1m]`은 "최근 1분 구간의 평균 증가율"이라는 뜻 — Phase 1 테스트처럼 짧은 시간에 몰아서 요청을 보내는 상황에선 `[1m]`이 적당하고, 데이터가 뜸하면 `[5m]`처럼 늘려야 그래프가 끊기지 않는다.
3. 순수 누적 값 자체를 보고 싶으면 별도 패널에 `pg_client_fallback_total`(rate 없이)도 추가해두면 좋다 — "총 몇 번 발생했는지" vs "지금 얼마나 자주 발생하는지"를 각각 확인 가능
4. 패널 제목을 "PG Fallback Rate"로 지정 후 저장

**확인 팁**: step 4의 강제 실패 반복 요청(`forceFail=true`) 중 OPEN 전이가 일어난 이후 구간에서 이 그래프가 올라가는지 확인 — OPEN 이전에는 `approveFallback`이 아직 안 불렸으니 0이어야 정상이다.

---

## 5. 데이터가 안 보일 때 체크리스트

- [ ] commerce-api가 떠 있는가 (`localhost:8081/actuator/prometheus`에 직접 접속해서 메트릭 텍스트가 나오는지 확인 — 8080 아님, actuator는 관리 포트에 있음)
- [ ] Prometheus 타겟이 UP인가 (`localhost:9090/targets`)
- [ ] 쿼리한 메트릭 이름이 정확한가 (`resilience4j_circuitbreaker_state`처럼 라이브러리가 자동 노출하는 이름은 오타에 취약함 — `/actuator/prometheus` 응답에서 실제 이름을 검색해서 확인)
- [ ] Grafana 패널의 시간 범위가 데이터가 쌓인 시점을 포함하는지 (우측 상단 time picker, 기본값이 "Last 6 hours"인데 방금 생긴 데이터인지 확인)
