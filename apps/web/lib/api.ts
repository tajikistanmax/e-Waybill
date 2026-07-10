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

export const md = {
  organizations: () => fetch('/md-api/api/v1/organizations', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  drivers: (orgRma: string) => fetch(`/md-api/api/v1/drivers?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  vehicles: (orgRma: string) => fetch(`/md-api/api/v1/vehicles?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  employees: (orgRma: string) => fetch(`/md-api/api/v1/employees?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
};

export const wb = {
  list: () => fetch('/wb-api/api/v1/waybills', { headers: authHeaders() }).then(r => handle<Waybill[]>(r)),
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
