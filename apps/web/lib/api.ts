// Клиент API платформы ЭПД РТ (dev: прокси через next.config rewrites)

let authToken = '';

export function setAuthToken(token: string) {
  authToken = token;
}

export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  return { ...(extra ?? {}), ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}) };
}

export type Waybill = {
  id: string;
  number: string | null;
  waybillType: string;
  status: string;
  medPassed: boolean;
  techPassed: boolean;
  validFrom: string | null;
  validTo: string | null;
  organizationRma: string;
  vehicleRegNumber: string;
  driverRma: string;
  dispatcherRma: string | null;
  route: string | null;
  schedule: string | null;
  odometerExit: number | null;
  odometerEntry: number | null;
  organizationSnapshot?: Record<string, unknown>;
  vehicleSnapshot?: Record<string, unknown>;
  driverSnapshot?: Record<string, unknown>;
  secondDriverRma?: string | null;
  typeData?: Record<string, unknown> | null;
  specialMark?: string | null;
  createdAt: string;
};

export type Title = {
  id: string;
  titleType: string;
  signerRma: string;
  signerRole: string;
  signedAt: string;
  data?: Record<string, unknown>;
};

export type StatusEvent = {
  fromStatus: string | null;
  toStatus: string;
  actor: string | null;
  reason: string | null;
  createdAt: string;
};

export const STATUS_LABELS: Record<string, { label: string; color: string }> = {
  DRAFT: { label: 'Черновик', color: 'gray' },
  CREATED: { label: 'Ожидает осмотров', color: 'amber' },
  MED_REJECTED: { label: 'Медосмотр отклонён', color: 'red' },
  TECH_REJECTED: { label: 'Техосмотр отклонён', color: 'red' },
  AWAITING_PAYMENT: { label: 'Ожидает оплаты', color: 'amber' },
  PAID: { label: 'Оплачен', color: 'blue' },
  READY: { label: 'Готов к выдаче', color: 'blue' },
  ISSUED: { label: 'Выдан', color: 'blue' },
  ACTIVE: { label: 'Активен', color: 'green' },
  RETURNED: { label: 'Возвращён', color: 'teal' },
  COMPLETED: { label: 'Завершён', color: 'gray' },
  CANCELLED: { label: 'Аннулирован', color: 'red' },
  EXPIRED: { label: 'Просрочен', color: 'red' },
  BLOCKED: { label: 'Заблокирован', color: 'red' },
  ARCHIVED: { label: 'В архиве', color: 'gray' },
};

export const TYPE_LABELS: Record<string, string> = {
  WB_CAR: 'Легковой (3-С)',
  WB_TAXI: 'Такси (3-С)',
  WB_MINIBUS: 'Микроавтобус (1-А)',
  WB_BUS: 'Автобус Т(1-АД)',
  WB_TROLLEYBUS: 'Троллейбус Т(1-АД)',
  WB_TRUCK: 'Грузовой (2-Б)',
  WB_TRUCK_INTL: 'Грузовой международный (5Б-БМ)',
  WB_PAX_INTL: 'Пассажирский международный (4М-БМ)',
  WB_SPECIAL: 'Спецтехника',
  WB_DANGEROUS: 'Опасные грузы',
};

export type Policy = {
  id: string;
  scopeLevel: 'NATIONAL' | 'ORGANIZATION' | 'VEHICLE_TYPE';
  scopeKey: string;
  ruleKey: string;
  ruleValue: string;
  enabled: boolean;
  updatedBy?: string | null;
  updatedAt?: string | null;
};

export type AuditEntry = {
  id: string;
  occurredAt: string;
  actor: string | null;
  actorOrg: string | null;
  action: 'CREATE' | 'UPDATE' | 'DELETE';
  entityType: string;
  entityKey: string | null;
  oldValue: string | null;
  newValue: string | null;
};

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = `Ошибка ${res.status}`;
    try {
      const problem = await res.json();
      detail = problem.detail || problem.title || detail;
      if (problem.errors) {
        detail += ': ' + problem.errors.map((e: { field: string; message: string }) => `${e.field} — ${e.message}`).join('; ');
      }
    } catch { /* ignore */ }
    throw new Error(detail);
  }
  return res.json();
}

function mdPost(path: string, body: unknown) {
  return fetch(`/md-api/api/v1/${path}`, {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  }).then(r => handle<Record<string, unknown>>(r));
}

export const md = {
  organizations: () => fetch('/md-api/api/v1/organizations', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  drivers: (orgRma: string) => fetch(`/md-api/api/v1/drivers?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  vehicles: (orgRma: string) => fetch(`/md-api/api/v1/vehicles?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  employees: (orgRma: string) => fetch(`/md-api/api/v1/employees?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  // Глобальные реестры (все организации). Для платформенного админа возвращают всё,
  // для арендо-ограниченного пользователя (COMPANY_ADMIN и т.п.) — только свою организацию.
  allDrivers: () => fetch('/md-api/api/v1/drivers', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  allVehicles: () => fetch('/md-api/api/v1/vehicles', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  allEmployees: () => fetch('/md-api/api/v1/employees', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  // Прямое создание/обновление (upsert) записей справочника — полный ручной ввод всех полей.
  // Соответствует POST /organizations|/drivers|/vehicles|/employees (роль SYSTEM_ADMIN / API_INTEGRATOR).
  createOrganization: (body: Record<string, unknown>) => mdPost('organizations', body),
  createDriver: (body: Record<string, unknown>) => mdPost('drivers', body),
  createVehicle: (body: Record<string, unknown>) => mdPost('vehicles', body),
  createEmployee: (body: Record<string, unknown>) => mdPost('employees', body),
  // Движок бизнес-правил (политик) — административная подсистема «Настройки».
  policies: () => fetch('/md-api/api/v1/policies', { headers: authHeaders() }).then(r => handle<Policy[]>(r)),
  savePolicy: (body: Record<string, unknown>) => mdPost('policies', body) as Promise<Policy>,
  deletePolicy: (id: string) => fetch(`/md-api/api/v1/policies/${id}`, { method: 'DELETE', headers: authHeaders() })
    .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  // Журнал аудита (только SYSTEM_ADMIN).
  audit: (entityType?: string, limit = 100) => fetch(
    `/md-api/api/v1/audit?limit=${limit}${entityType ? `&entityType=${entityType}` : ''}`,
    { headers: authHeaders() }).then(r => handle<AuditEntry[]>(r)),
};

export type Payment = {
  id: string;
  waybillId: string;
  amount: number;
  currency: string;
  status: 'PENDING' | 'CONFIRMED';
  method: string | null;
  externalRef: string | null;
  createdAt: string;
  confirmedAt: string | null;
  confirmedBy: string | null;
};

export const wb = {
  list: () => fetch('/wb-api/api/v1/waybills', { headers: authHeaders() }).then(r => handle<Waybill[]>(r)),
  payment: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/payment`, { headers: authHeaders() }).then(r => handle<Payment>(r)),
  get: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}`, { headers: authHeaders() }).then(r => handle<Waybill>(r)),
  titles: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/titles`, { headers: authHeaders() }).then(r => handle<Title[]>(r)),
  history: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/status-history`, { headers: authHeaders() }).then(r => handle<StatusEvent[]>(r)),
  qr: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/qr`, { headers: authHeaders() }).then(r => handle<{ jws: string }>(r)),
  post: <T = Waybill>(path: string, body?: unknown) =>
    fetch(`/wb-api/api/v1/waybills${path}`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: body != null ? JSON.stringify(body) : '{}',
    }).then(r => handle<T>(r)),
};
