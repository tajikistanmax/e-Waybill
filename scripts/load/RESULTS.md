# e-Roхxat load test results

## Замер 2026-09-24 — через HTTPS-прокси, с реалистичной раскладкой по учёткам

Путь настоящего пользователя: k6 → nginx (TLS, заголовки, лимит входа) → web (Next.js) →
waybill / master-data. Вход — платформенный (`/api/v1/auth/token`; Keycloak из стека убран).
Запуск: `... grafana/k6 run -e VIA_PROXY=1 /scripts/epd-load-test.js`, полный вывод —
`run-output-proxy-20260924.txt`. Стенд со всеми изменениями 24.09 (Spring Boot 3.4.13, второй
фактор, прокси, проверки здоровья).

Сценарии те же (20 читателей + 5 писателей одновременно, ~2 мин), но:
- читатели разнесены по шести учёткам (бухгалтер, администратор компании и филиала,
  администратор платформы, аналитик, инспектор), писатели — по временным диспетчеру, врачу и
  механику на поток (setup создаёт, teardown удаляет);
- писатель делает лист раз в 3,5–4,5 с (~13 в минуту на учётку — в разы быстрее человека).

| Показатель | p50 | p95 | ошибок |
|---|---|---|---|
| `GET /waybills` (список до 1000 листов, 500–600 в демо) | 230 мс | 409 мс | 0 из 671 |
| `GET /reports/summary` | 54 мс | 101 мс | 0 из 671 |
| создание листа | 62 мс | 89 мс | 0 |
| T1 / T2 / T3 | 35 / 29 / 34 мс | 53 / 51 / 52 мс | 0 |
| цепочка создание→T3 целиком | 166 мс | 222 мс | 0 из 71 |

Проверок 1697 из 1697; `http_req_failed` 0,77 % — это 15 ответов 428 при первом входе
временных учёток (смена временного пароля, так и задумано).

**Что показали промежуточные прогоны (исправлено или учтено):**
1. Прежний тест давал 20 потокам две учётки — ~350 запросов в минуту на учётку, 37 % ответов
   429 «слишком часто». Лимит на пользователя (300/мин) сработал верно, живой человек столько
   не делает — тест переделан на реалистичную раскладку.
2. **Потолок одной учётки-человека — около 18 созданий листа в минуту**: создание делает ~16
   справочных запросов в master-data тем же токеном. Диспетчеру (1–2 листа в минуту) это не
   мешает. **Агрегатору** (канал `/api/v1/aggregator`, массовая загрузка одной учёткой) мешало
   бы — поэтому служебным учёткам (роль `API_INTEGRATOR`) дан отдельный предел 3000/мин
   (`epd.ratelimit.integrator-capacity`); живая проверка: 400 запросов подряд агрегатором —
   400×200, диспетчером — 300×200 + 100×429, как и прежде.
3. Список `GET /waybills` отдаёт до 1000 листов целиком (~2 МБ); браузер получает его сжатым
   (gzip — ~80 КБ у диспетчера), k6 сжатие не запрашивает — отсюда 1,1 ГБ «принято» за прогон.
   Для прода приемлемо; переход страниц кабинетов на постраничный `/waybills/page` — отдельная
   задача.

Вывод: на пилотном масштабе (десятки одновременных пользователей) запаса по производительности
хватает с большим запасом; узкое место — не сервер, а пределы частоты на учётку, и они
соответствуют работе человека.

---

# First measurement (2026-09-03)

Ran against the live Docker stack (epd-prod-master-data :8081, epd-prod-waybill :8082,
epd-prod-keycloak :8180) using `epd-load-test.js` from this directory. This was the
first-ever capacity measurement of this platform - no prior baseline exists.

## How to reproduce

```
docker run --rm -i --add-host=host.docker.internal:host-gateway ^
  -v "D:\Projects\e-Waybill\scripts\load:/scripts" grafana/k6 run /scripts/epd-load-test.js
```

Run took ~2m03s end-to-end (setup + both scenarios + teardown). Full raw console
output from the run this note describes is saved alongside it as `run-output.txt`.

## What was run

Two scenarios executing **concurrently** (mixed read/write load, as real traffic would be):

- **read_heavy**: `GET /api/v1/waybills` (list) + `GET /api/v1/reports/summary?from=&to=`,
  alternating dispatcher/accountant tokens, ramped 0 -> 20 VUs over ~100s, held, ramped down.
- **write_chain**: full `create -> T1 (dispatcher) -> T2 confirm-med (doctor) -> T3
  confirm-tech (mechanic)` lifecycle, ramped 0 -> 5 VUs over ~90s.

**Handling the "one active waybill per vehicle/driver" invariant**: chose a combination of
option (a) and (b) from the brief, because either alone doesn't fully work here:
- `setup()` seeds a pool of 20 dummy vehicles (`LOADV001TJ..LOADV020TJ`, transportType=1)
  and 20 dummy drivers (reserved numeric RMA block `999000001..999000020`, licenseCategories=D)
  via the admin (`admin`/`admin`) master-data API - option (a). Each write_chain VU is
  pinned to its own pool slot (`slot = (__VU-1) % poolSize`), so concurrent VUs never touch
  the same vehicle. Pool size (20) is well above the scenario's peak of 5 VUs to absorb any
  VU-id gaps from ramp-up/ramp-down overlap.
- Pure pool-per-VU isn't enough on its own: the chain deliberately stops at T3
  (`AWAITING_PAYMENT`, per the brief's "create -> T1 -> T2 -> T3" scope) rather than
  running to completion, so the vehicle would stay "occupied" for that VU's *next*
  iteration. So each iteration also cancels its own waybill via `/cancel` at the end -
  option (b) - freeing the slot for the same VU's next pass. Zero pool collisions
  (409s) occurred in the run (the script counts them separately as
  `write_chain_pool_collision_total` and it never fired).

Tokens (~300s lifetime) are cached per-VU with a 200s refresh window rather than fetched
fresh on every single request, avoiding both the "one token reused for 28 slow sequential
requests" failure mode from the earlier manual script and needless Keycloak load.

## Results

All numbers from a single ~123s run, 989 total iterations, 2952 HTTP requests, 0 failures.

### Read-heavy (dispatcher/accountant, up to 20 VUs)

| Endpoint | p50 | p90 | p95 | max | error rate |
|---|---|---|---|---|---|
| `GET /waybills` (list) | 69ms | 105ms | 120ms | 405ms | 0.00% |
| `GET /reports/summary` | 25ms | 37ms | 42ms | 72ms | 0.00% |

726 read iterations (1452 requests), all 200 OK.

### Write chain (create -> T1 -> T2 -> T3, up to 5 VUs)

| Step | p50 | p90 | p95 | max |
|---|---|---|---|---|
| create | 45ms | 72ms | 82ms | 318ms |
| T1 (dispatcher) | 24ms | 38ms | 42ms | 69ms |
| T2 confirm-med (doctor) | 25ms | 39ms | 44ms | 75ms |
| T3 confirm-tech (mechanic) | 29ms | 44ms | 50ms | 82ms |
| **full chain (create..T3)** | **128ms** | **194ms** | **219ms** | **411ms** |
| cancel (cleanup, not counted in chain) | 12ms | 18ms | 20ms | 48ms |

263 write-chain iterations, 100% completed create->T1->T2->T3 successfully
(`write_error_rate` = 0.00%), 0 pool collisions.

### Overall

- `http_req_failed`: 0.00% (0 of 2952 requests)
- `checks_succeeded`: 100.00% (2767 of 2767)
- Combined throughput: ~24 req/s, ~8 iterations/s across both scenarios at peak (25 VUs total)
- No timeouts, no 5xx responses, no connection resets, no threshold breaches anywhere
  in the run (see THRESHOLDS section of `run-output.txt`)

## Honest read for pilot readiness

For a pilot with a handful of carriers, this looks solidly fine, not just "looks fine"
on paper but genuinely unremarkable under a realistic mixed load: sub-150ms p95 for every
individual step, a ~220ms p95 for the entire 4-step create-to-AWAITING_PAYMENT chain, zero
errors, zero degradation as VUs ramped to the target concurrency. 20 concurrent readers +
5 concurrent writers is already generous headroom over what "a handful of carriers'
dispatchers/accountants clicking around" would produce in practice.

Caveats worth keeping in mind rather than over-trusting a green run:
- This is a single ~2-minute run on a single machine also running several other Docker
  stacks (fleetbase, roadlist-demo, etc. were competing for CPU/IO throughout). Numbers
  could shift under a quieter host or a longer soak.
- The write chain only exercises create/T1/T2/T3; it does not touch confirm-payment,
  issue/activate, GPS ingestion, or reporting exports, which are separate code paths with
  their own DB writes and weren't load tested here.
- No sustained/soak test was run (e.g. 30+ minutes) to catch slow leaks, connection-pool
  exhaustion, or GC pressure that a 2-minute run can't surface.
- Concurrency here is deliberately pilot-scale (max 25 VUs total). This says nothing about
  behavior at 10x-100x that scale - it wasn't meant to, but don't extrapolate from it.

No 5xx/timeouts/anomalies were observed, so there's nothing alarming to flag for launch
readiness from this test - the honest conclusion is "no capacity red flags found," not
"capacity is proven at scale."

## Cleanup

- `teardown()` cancelled any still-open LOAD-pool waybills, then deleted all 20 pool
  vehicles and 20 pool drivers via the admin API. Verified post-run: 0 leftover LOAD
  vehicles/drivers in master-data, 0 LOAD waybills left in a non-terminal status.
- **263 CANCELLED waybill records remain** in the waybill-service DB for organization
  025680800 (vehicle reg numbers `LOADV001TJ`..`LOADV020TJ`, route `k6-load-route`, cancel
  reason `k6-load-cleanup`). This is expected and is the correct end state given the
  platform's design: waybill-service has no delete endpoint for waybills at all (only
  `/cancel`, by deliberate immutability/audit-trail design - see the "принцип
  иммутабельности" comment in WaybillService). Cancelling was the actual cleanup contract
  available via the API; the records are clearly tagged and terminal, not "ambiguous" or
  open/active. They will not collide with future load-test runs or with real demo data,
  but a human reviewing waybill history for org 025680800 will see them - filter on
  `vehicleRegNumber like 'LOADV%'` or `route = 'k6-load-route'` to exclude them.
- The shared demo fixtures reused by `setup()` (organization `025680800`, employees
  `111111111`/`222222222`/`333333333`) were only idempotently upserted (same shape as
  `smoke-test.ps1` already uses) - nothing new or ambiguous was created there.
