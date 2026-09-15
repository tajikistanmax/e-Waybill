// =====================================================================
// Нагрузочный тест платформы e-Роҳхат (первое измерение, пилот-масштаб)
//
// Запуск (из корня репозитория, k6 в Docker, backend уже поднят на хосте):
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -v <abs-path-to-repo>\scripts\load:/scripts grafana/k6 run /scripts/epd-load-test.js
//
// Два сценария выполняются ОДНОВРЕМЕННО (реалистичная смесь чтение+запись):
//   read_heavy  - GET /waybills (список) + GET /reports/summary, рампа до ~20 VU
//   write_chain - create -> T1 -> T2 -> T3 (create/dispatcher, med/doctor, tech/mechanic),
//                 рампа до ~5 VU
//
// Токены Keycloak живут ~300с - НЕ кэшируются на весь прогон общей переменной;
// каждый VU держит свой кэш токенов (модульная область видимости = per-VU в k6)
// и обновляет их, если токену больше ~200с.
//
// Инвариант "один действующий ПЛ на ТС/водителя": write_chain использует пул из
// заранее засеянных (setup()) фиктивных ТС/водителей ("LOAD..." / зарезервированный
// РМА-блок 999xxxxxx), закреплённых по одному за VU (slot = (__VU-1) % poolSize),
// и в конце каждой итерации отменяет свой ПЛ (/cancel), освобождая слот для
// следующей итерации того же VU. Это сочетание варианта (a) и (b) из задания:
// пул нужен, чтобы РАЗНЫЕ VU не сталкивались друг с другом одновременно, а cancel
// нужен, чтобы ОДИН И ТОТ ЖЕ VU мог повторно использовать свой слот на следующей
// итерации (иначе он бы столкнулся сам с собой на втором проходе).
// =====================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

// ------------------------------------------------------------- конфигурация

const KC = 'http://host.docker.internal:8180';
const MD = 'http://host.docker.internal:8081';
const WB = 'http://host.docker.internal:8082';
const ORG_RMA = '025680800'; // существующая демо-организация (реиспользуем, не создаём мусор)
const WRITE_POOL_SIZE = 20;  // запас против пересечения __VU-слотов при ramp-up/down
const TOKEN_TTL_MS = 200000; // обновлять токен, если старше 200с (токен живёт ~300с)

// Пароли демо-учёток реалма epd. Пароль = логину больше не проходит (парольная
// политика реалма, см. infra/keycloak/epd-realm.json) — синхронизировано с
// scripts/demo-credentials.ps1.
const DEMO_PASSWORDS = {
  dispatcher: 'Epd-Qa-Tanzim-2026',
  doctor: 'Epd-Qa-Duxtur-2026',
  mechanic: 'Epd-Qa-Mexanik-2026',
  accountant: 'Epd-Qa-Buxgalter-2026',
  // admin требует CONFIGURE_TOTP (2FA, ИБ-13.2.2) — grant_type=password для него
  // больше не проходит. Нагрузочный тест использует admin-automation той же роли.
  'admin-automation': 'Epd-Qa-Automation-Admin-2026',
};
function pw(username) {
  return DEMO_PASSWORDS[username] || username;
}

// ------------------------------------------------------------- метрики

const readListDuration = new Trend('read_list_duration', true);
const readSummaryDuration = new Trend('read_summary_duration', true);
const readErrorRate = new Rate('read_error_rate');
const readRequests = new Counter('read_requests_total');

const writeChainDuration = new Trend('write_chain_duration', true);
const writeCreateDuration = new Trend('write_create_duration', true);
const writeT1Duration = new Trend('write_t1_duration', true);
const writeMedDuration = new Trend('write_med_duration', true);
const writeTechDuration = new Trend('write_tech_duration', true);
const writeCancelDuration = new Trend('write_cancel_duration', true);
const writeChainSuccess = new Counter('write_chain_success_total');
const writeChainFailure = new Counter('write_chain_failure_total');
const writeChainCollision = new Counter('write_chain_pool_collision_total'); // 409 на create (ожидаемо редко)
const writeErrorRate = new Rate('write_error_rate');

// ------------------------------------------------------------- опции / сценарии

export const options = {
  scenarios: {
    read_heavy: {
      executor: 'ramping-vus',
      exec: 'readHeavy',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 5 },
        { duration: '40s', target: 20 },
        { duration: '40s', target: 20 },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
    write_chain: {
      executor: 'ramping-vus',
      exec: 'writeChain',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 2 },
        { duration: '60s', target: 5 },
        { duration: '15s', target: 5 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  // Исследовательские пороги - не гейт для этого прогона (SLA ещё не определён),
  // просто дают наглядную PASS/FAIL-отметку в сводке k6.
  thresholds: {
    read_error_rate: [{ threshold: 'rate<0.05', abortOnFail: false }],
    write_error_rate: [{ threshold: 'rate<0.05', abortOnFail: false }],
    read_list_duration: [{ threshold: 'p(95)<3000', abortOnFail: false }],
    write_chain_duration: [{ threshold: 'p(95)<5000', abortOnFail: false }],
  },
};

// ------------------------------------------------------------- утилиты

function fmtDate(d) {
  return d.toISOString().slice(0, 10);
}

function plusMonths(months) {
  const d = new Date();
  d.setMonth(d.getMonth() + months);
  return fmtDate(d);
}

function plusYears(years) {
  const d = new Date();
  d.setFullYear(d.getFullYear() + years);
  return fmtDate(d);
}

function jsonHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json; charset=utf-8',
  };
}

// Кэш токенов - объявлен на уровне модуля => отдельный экземпляр на каждый VU
// (k6 инициализирует модуль заново для каждого VU). Внутри одного VU кэш живёт
// между итерациями и обновляется по TTL, а не переиспользуется на весь прогон.
const tokenCache = {};

function fetchToken(username, password) {
  const body = `client_id=epd-web&grant_type=password&username=${username}&password=${password}`;
  const res = http.post(`${KC}/realms/epd/protocol/openid-connect/token`, body, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    tags: { name: 'token' },
  });
  if (res.status !== 200) {
    throw new Error(`Не удалось получить токен для ${username}: HTTP ${res.status} ${res.body}`);
  }
  return res.json('access_token');
}

function getToken(username, password) {
  const cached = tokenCache[username];
  const now = Date.now();
  if (cached && now - cached.ts < TOKEN_TTL_MS) {
    return cached.token;
  }
  const token = fetchToken(username, password);
  tokenCache[username] = { token, ts: now };
  return token;
}

function postJson(url, headers, obj, tagName) {
  return http.post(url, JSON.stringify(obj), { headers, tags: { name: tagName } });
}

// ------------------------------------------------------------- setup (один раз)

export function setup() {
  const adminToken = fetchToken('admin-automation', pw('admin-automation'));
  const adminHeaders = jsonHeaders(adminToken);

  const farFuture = plusYears(2);
  const nearFuture = plusMonths(6);

  // 1) Организация (идемпотентный upsert существующей демо-организации - не мусор).
  const orgRes = postJson(`${MD}/api/v1/organizations`, adminHeaders, {
    rma: ORG_RMA, name: 'КВД Автобуси Душанбе', typeCompany: 1, regionId: 1,
    licenseFrom: '2025-01-01', licenseTo: farFuture,
  }, 'setup-organization');
  if (orgRes.status >= 400) {
    console.error(`setup: организация - HTTP ${orgRes.status} ${orgRes.body}`);
  }

  // 2) Сотрудники врач/механик/диспетчер (идемпотентный upsert, те же РМА, что в smoke-test.ps1).
  const employees = [
    { rma: '111111111', name: 'Духтур Тестовый', type: 1 },
    { rma: '222222222', name: 'Механик Тестовый', type: 2 },
    { rma: '333333333', name: 'Диспетчер Тестовый', type: 3 },
  ];
  employees.forEach((e) => {
    const r = postJson(`${MD}/api/v1/employees`, adminHeaders, {
      rma: e.rma, organizationRma: ORG_RMA, name: e.name, type: e.type,
    }, 'setup-employee');
    if (r.status >= 400) {
      console.error(`setup: сотрудник ${e.rma} - HTTP ${r.status} ${r.body}`);
    }
  });

  // 3) Пул фиктивных ТС/водителей для write_chain, явно опознаваемых как нагрузочные:
  //    госномер с префиксом LOAD, РМА водителя из зарезервированного блока 999xxxxxx.
  const vehiclePool = [];
  const driverPool = [];
  for (let i = 1; i <= WRITE_POOL_SIZE; i++) {
    const plate = `LOADV${String(i).padStart(3, '0')}TJ`;
    const driverRma = `999${String(i).padStart(6, '0')}`;

    const vRes = postJson(`${MD}/api/v1/vehicles`, adminHeaders, {
      registrationNumber: plate, organizationRma: ORG_RMA, transportType: 1,
      brand: 'LOAD-TEST', techInspectionValidTo: nearFuture, controlCardValidTo: nearFuture,
    }, 'setup-vehicle');
    if (vRes.status >= 400) {
      console.error(`setup: ТС ${plate} - HTTP ${vRes.status} ${vRes.body}`);
    }

    const dRes = postJson(`${MD}/api/v1/drivers`, adminHeaders, {
      rma: driverRma, organizationRma: ORG_RMA, fullName: `LOAD-TEST Driver ${i}`,
      licenseNumber: `LOAD${i}`, licenseCategories: 'D',
      licenseValidTo: farFuture, medCertValidTo: nearFuture,
    }, 'setup-driver');
    if (dRes.status >= 400) {
      console.error(`setup: водитель ${driverRma} - HTTP ${dRes.status} ${dRes.body}`);
    }

    vehiclePool.push(plate);
    driverPool.push(driverRma);
  }

  // 4) Защитная очистка: если предыдущий прогон прервался, на пуле могли остаться
  //    незакрытые ПЛ, блокирующие инвариант "один действующий ПЛ на ТС" в этом прогоне.
  const dispatcherToken = fetchToken('dispatcher', pw('dispatcher'));
  const dispatcherHeaders = jsonHeaders(dispatcherToken);
  const openStatuses = ['DRAFT', 'CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE'];
  const listRes = http.get(`${WB}/api/v1/waybills?organizationRma=${ORG_RMA}`, {
    headers: dispatcherHeaders, tags: { name: 'setup-list' },
  });
  let cleaned = 0;
  if (listRes.status === 200) {
    const list = listRes.json();
    list
      .filter((w) => vehiclePool.indexOf(w.vehicleRegNumber) !== -1 && openStatuses.indexOf(w.status) !== -1)
      .forEach((w) => {
        postJson(`${WB}/api/v1/waybills/${w.id}/cancel`, dispatcherHeaders, {
          reason: 'k6-load-setup-cleanup', actor: 'k6',
        }, 'setup-cancel');
        cleaned++;
      });
  } else {
    console.error(`setup: не удалось получить список ПЛ для очистки - HTTP ${listRes.status}`);
  }
  console.log(`setup: пул готов (${vehiclePool.length} ТС/водителей), очищено зависших ПЛ: ${cleaned}`);

  return { vehiclePool, driverPool };
}

// ------------------------------------------------------------- сценарий 1: чтение

export function readHeavy() {
  // Половина итераций - диспетчер, половина - бухгалтер (обе роли читают отчёты/списки).
  const asDispatcher = __ITER % 2 === 0;
  const user = asDispatcher ? 'dispatcher' : 'accountant';
  const token = getToken(user, pw(user));
  const headers = { Authorization: `Bearer ${token}` };

  const listRes = http.get(`${WB}/api/v1/waybills`, { headers, tags: { name: 'read-list' } });
  readListDuration.add(listRes.timings.duration);
  readRequests.add(1);
  const listOk = check(listRes, { 'GET /waybills -> 200': (r) => r.status === 200 });
  readErrorRate.add(!listOk);

  const to = fmtDate(new Date());
  const fromD = new Date();
  fromD.setDate(fromD.getDate() - 30);
  const from = fmtDate(fromD);
  const sumRes = http.get(`${WB}/api/v1/reports/summary?from=${from}&to=${to}`, {
    headers, tags: { name: 'read-summary' },
  });
  readSummaryDuration.add(sumRes.timings.duration);
  readRequests.add(1);
  const sumOk = check(sumRes, { 'GET /reports/summary -> 200': (r) => r.status === 200 });
  readErrorRate.add(!sumOk);

  sleep(Math.random() * 2 + 1); // 1-3с "думает" между запросами
}

// ------------------------------------------------------------- сценарий 2: цепочка записи

export function writeChain(data) {
  const poolSize = data.vehiclePool.length;
  const slot = (__VU - 1) % poolSize;
  const vehicle = data.vehiclePool[slot];
  const driver = data.driverPool[slot];

  const dispatcherHeaders = jsonHeaders(getToken('dispatcher', pw('dispatcher')));
  const doctorHeaders = jsonHeaders(getToken('doctor', pw('doctor')));
  const mechanicHeaders = jsonHeaders(getToken('mechanic', pw('mechanic')));

  const chainStart = Date.now();
  let waybillId = null;
  let ok = true;

  // create -> DRAFT
  const createRes = postJson(`${WB}/api/v1/waybills`, dispatcherHeaders, {
    waybillType: 'WB_BUS', organizationRma: ORG_RMA, vehicleRegNumber: vehicle,
    driverRma: driver, communicationType: 'URBAN', route: 'k6-load-route',
  }, 'write-create');
  writeCreateDuration.add(createRes.timings.duration);
  if (createRes.status === 409) {
    // Ожидаемо редкий случай столкновения на слоте (не ошибка сервиса) - считаем отдельно.
    writeChainCollision.add(1);
    writeErrorRate.add(true);
    return;
  }
  const createOk = check(createRes, { 'create -> 201 DRAFT': (r) => r.status === 201 && r.json('status') === 'DRAFT' });
  if (!createOk) {
    ok = false;
  } else {
    waybillId = createRes.json('id');
  }

  // T1 (диспетчер) -> CREATED
  if (ok) {
    const t1Res = postJson(`${WB}/api/v1/waybills/${waybillId}/titles/t1`, dispatcherHeaders, {
      dispatcherRma: '333333333', validityDays: 1,
    }, 'write-t1');
    writeT1Duration.add(t1Res.timings.duration);
    const t1Ok = check(t1Res, { 'T1 -> 200 CREATED': (r) => r.status === 200 && r.json('status') === 'CREATED' });
    if (!t1Ok) ok = false;
  }

  // T2 медосмотр (врач)
  if (ok) {
    const medRes = postJson(`${WB}/api/v1/waybills/${waybillId}/confirm-med`, doctorHeaders, {
      employeeRma: '111111111', passed: true, indicators: { pulse: 70, alcotest: 0 },
    }, 'write-med');
    writeMedDuration.add(medRes.timings.duration);
    const medOk = check(medRes, { 'T2 -> 200 medPassed': (r) => r.status === 200 && r.json('medPassed') === true });
    if (!medOk) ok = false;
  }

  // T3 техконтроль (механик) -> AWAITING_PAYMENT
  if (ok) {
    const techRes = postJson(`${WB}/api/v1/waybills/${waybillId}/confirm-tech`, mechanicHeaders, {
      employeeRma: '222222222', passed: true, checklist: { brakes: 'OK' },
    }, 'write-tech');
    writeTechDuration.add(techRes.timings.duration);
    const techOk = check(techRes, {
      'T3 -> 200 AWAITING_PAYMENT': (r) => r.status === 200 && r.json('status') === 'AWAITING_PAYMENT',
    });
    if (!techOk) ok = false;
  }

  writeChainDuration.add(Date.now() - chainStart);
  writeErrorRate.add(!ok);
  if (ok) {
    writeChainSuccess.add(1);
  } else {
    writeChainFailure.add(1);
  }

  // Освобождаем слот для следующей итерации ЭТОГО ЖЕ VU (не входит в замер цепочки).
  if (waybillId) {
    const cancelRes = postJson(`${WB}/api/v1/waybills/${waybillId}/cancel`, dispatcherHeaders, {
      reason: 'k6-load-cleanup', actor: 'k6',
    }, 'write-cancel');
    writeCancelDuration.add(cancelRes.timings.duration);
    check(cancelRes, { 'cancel -> 200': (r) => r.status === 200 });
  }

  sleep(Math.random() * 1 + 0.5); // 0.5-1.5с между итерациями одного VU
}

// ------------------------------------------------------------- teardown (финальная очистка)

export function teardown(data) {
  const dispatcherHeaders = jsonHeaders(fetchToken('dispatcher', pw('dispatcher')));
  const openStatuses = ['DRAFT', 'CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE'];
  const listRes = http.get(`${WB}/api/v1/waybills?organizationRma=${ORG_RMA}`, {
    headers: dispatcherHeaders, tags: { name: 'teardown-list' },
  });
  let cleanedWaybills = 0;
  if (listRes.status === 200) {
    const list = listRes.json();
    list
      .filter((w) => data.vehiclePool.indexOf(w.vehicleRegNumber) !== -1 && openStatuses.indexOf(w.status) !== -1)
      .forEach((w) => {
        postJson(`${WB}/api/v1/waybills/${w.id}/cancel`, dispatcherHeaders, {
          reason: 'k6-load-teardown-cleanup', actor: 'k6',
        }, 'teardown-cancel');
        cleanedWaybills++;
      });
  } else {
    console.error(`teardown: не удалось получить список ПЛ - HTTP ${listRes.status}`);
  }

  // Полная зачистка мастер-данных нагрузочного пула (админ вправе удалять ТС/водителей).
  const adminHeaders = jsonHeaders(fetchToken('admin-automation', pw('admin-automation')));
  let deletedVehicles = 0;
  let deletedDrivers = 0;
  data.vehiclePool.forEach((plate) => {
    const r = http.get(`${MD}/api/v1/vehicles?registrationNumber=${plate}`, {
      headers: adminHeaders, tags: { name: 'teardown-find-vehicle' },
    });
    if (r.status === 200) {
      const found = r.json();
      found.forEach((v) => {
        const del = http.del(`${MD}/api/v1/vehicles/${v.id}`, null, {
          headers: adminHeaders, tags: { name: 'teardown-delete-vehicle' },
        });
        if (del.status === 204) deletedVehicles++;
      });
    }
  });
  data.driverPool.forEach((rma) => {
    const r = http.get(`${MD}/api/v1/drivers?rma=${rma}`, {
      headers: adminHeaders, tags: { name: 'teardown-find-driver' },
    });
    if (r.status === 200) {
      const found = r.json();
      found.forEach((d) => {
        const del = http.del(`${MD}/api/v1/drivers/${d.id}`, null, {
          headers: adminHeaders, tags: { name: 'teardown-delete-driver' },
        });
        if (del.status === 204) deletedDrivers++;
      });
    }
  });

  console.log(`teardown: отменено зависших ПЛ на LOAD-пуле: ${cleanedWaybills}; `
    + `удалено ТС: ${deletedVehicles}/${data.vehiclePool.length}, водителей: ${deletedDrivers}/${data.driverPool.length}`);
}
