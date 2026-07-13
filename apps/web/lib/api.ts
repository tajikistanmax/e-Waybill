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
  // Электронная подпись титула (TitleSigner): dev — SHA-256, прод — CAdES УЦ РТ. На бланке
  // печатается усечённый отпечаток как доказательство подписи в системе.
  signature?: string;
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
  clientIp: string | null;
  userAgent: string | null;
};

export type ExpiryItem = {
  entityType: 'DRIVER' | 'VEHICLE' | 'ORGANIZATION';
  key: string;
  name: string;
  docType: string;
  validTo: string;
  daysLeft: number;
};

export type GpsPing = {
  id: string;
  vehicleRegNumber: string;
  lat: number;
  lon: number;
  speedKmh: number | null;
  recordedAt: string;
};

export type NeruView = {
  number: string | null;
  status: string;
  vehicleRegNumber: string;
  driverName: string | null;
  organizationRma: string;
  validFrom: string | null;
  validTo: string | null;
};

export type FieldDefinition = {
  id: string;
  waybillType: string;
  fieldKey: string;
  labelRu: string;
  labelTj: string | null;
  dataType: 'STRING' | 'NUMBER' | 'DATE' | 'BOOLEAN' | 'ENUM';
  required: boolean;
  options: string | null;
  sortOrder: number;
  active: boolean;
};

export type ClassifierItem = {
  id: string;
  category: string;
  code: string;
  nameRu: string;
  nameTj: string | null;
  sortOrder: number;
  active: boolean;
};

export type NotificationItem = {
  id: string;
  recipientRma: string;
  waybillId: string | null;
  kind: string;
  title: string;
  body: string | null;
  createdAt: string;
  readAt: string | null;
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

function mdDelete(path: string): Promise<void> {
  return fetch(`/md-api/api/v1/${path}`, { method: 'DELETE', headers: authHeaders() })
    .then(async r => {
      if (!r.ok && r.status !== 204) {
        const p = await r.json().catch(() => null);
        throw new Error(p?.detail ?? p?.title ?? `Ошибка ${r.status}`);
      }
    });
}

export const md = {
  organizations: () => fetch('/md-api/api/v1/organizations', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  drivers: (orgRma: string) => fetch(`/md-api/api/v1/drivers?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  vehicles: (orgRma: string) => fetch(`/md-api/api/v1/vehicles?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  employees: (orgRma: string) => fetch(`/md-api/api/v1/employees?organizationRma=${orgRma}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  // Серверный подстрочный поиск (для автопарков в тысячи ТС/водителей): ТС по госномеру,
  // водители по ИНН или ФИО. Пустой q → первые limit (просмотр). Org-скоуп на бэкенде.
  searchVehicles: (orgRma: string, q: string, limit = 25) => fetch(
    `/md-api/api/v1/vehicles?organizationRma=${encodeURIComponent(orgRma)}&q=${encodeURIComponent(q)}&limit=${limit}`,
    { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  searchDrivers: (orgRma: string, q: string, limit = 25) => fetch(
    `/md-api/api/v1/drivers?organizationRma=${encodeURIComponent(orgRma)}&q=${encodeURIComponent(q)}&limit=${limit}`,
    { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  // Добавление ТС/водителя своей организации по госномеру/ИНН — данные из единой платформы
  // (ГАИ/налоговая). Доступно диспетчеру и администратору компании (tenant-скоуп на бэкенде).
  syncVehicle: (body: Record<string, unknown>) => mdPost('sync/vehicle', body),
  syncDriver: (body: Record<string, unknown>) => mdPost('sync/driver', body),
  // Глобальные реестры (все организации). Для платформенного админа возвращают всё,
  // для арендо-ограниченного пользователя (COMPANY_ADMIN и т.п.) — только свою организацию.
  allDrivers: () => fetch('/md-api/api/v1/drivers', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  allVehicles: () => fetch('/md-api/api/v1/vehicles', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  allEmployees: () => fetch('/md-api/api/v1/employees', { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  // Нативное управление субъектами/объектами внутри платформы — полный ручной ввод всех полей.
  // POST /organizations|/drivers|/vehicles|/employees: перевозчик (COMPANY_ADMIN/DISPATCHER) ведёт
  // СВОЮ организацию (тенант-защита на бэкенде), сисадмин/интегратор — любую.
  createOrganization: (body: Record<string, unknown>) => mdPost('organizations', body),
  createDriver: (body: Record<string, unknown>) => mdPost('drivers', body),
  createVehicle: (body: Record<string, unknown>) => mdPost('vehicles', body),
  createEmployee: (body: Record<string, unknown>) => mdPost('employees', body),
  // Удаление (COMPANY_ADMIN/SYSTEM_ADMIN; организация — только SYSTEM_ADMIN). История ПЛ цела (снимки).
  deleteDriver: (id: string) => mdDelete(`drivers/${id}`),
  deleteVehicle: (id: string) => mdDelete(`vehicles/${id}`),
  deleteEmployee: (id: string) => mdDelete(`employees/${id}`),
  deleteOrganization: (id: string) => mdDelete(`organizations/${id}`),
  // Движок бизнес-правил (политик) — административная подсистема «Настройки».
  policies: () => fetch('/md-api/api/v1/policies', { headers: authHeaders() }).then(r => handle<Policy[]>(r)),
  savePolicy: (body: Record<string, unknown>) => mdPost('policies', body) as Promise<Policy>,
  deletePolicy: (id: string) => fetch(`/md-api/api/v1/policies/${id}`, { method: 'DELETE', headers: authHeaders() })
    .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  // Классификаторы (страны, классы ADR, виды дозволов).
  classifiers: (category: string, all = false) => fetch(
    `/md-api/api/v1/classifiers?category=${category}${all ? '&all=true' : ''}`,
    { headers: authHeaders() }).then(r => handle<ClassifierItem[]>(r)),
  saveClassifier: (body: Record<string, unknown>) => mdPost('classifiers', body) as Promise<ClassifierItem>,
  deleteClassifier: (id: string) => fetch(`/md-api/api/v1/classifiers/${id}`, { method: 'DELETE', headers: authHeaders() })
    .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  // Конструктор полей: доп.поля по типу ПЛ.
  fieldDefinitions: (waybillType: string, all = false) => fetch(
    `/md-api/api/v1/field-definitions?waybillType=${waybillType}${all ? '&all=true' : ''}`,
    { headers: authHeaders() }).then(r => handle<FieldDefinition[]>(r)),
  saveFieldDefinition: (body: Record<string, unknown>) => mdPost('field-definitions', body) as Promise<FieldDefinition>,
  deleteFieldDefinition: (id: string) => fetch(`/md-api/api/v1/field-definitions/${id}`, { method: 'DELETE', headers: authHeaders() })
    .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  // Монитор истечения документов (тенант-скоуп).
  documentExpiry: (days = 30) => fetch(`/md-api/api/v1/document-expiry?days=${days}`, { headers: authHeaders() })
    .then(r => handle<ExpiryItem[]>(r)),
  // Журнал аудита (только SYSTEM_ADMIN).
  audit: (entityType?: string, limit = 100) => fetch(
    `/md-api/api/v1/audit?limit=${limit}${entityType ? `&entityType=${entityType}` : ''}`,
    { headers: authHeaders() }).then(r => handle<AuditEntry[]>(r)),
  // Доступ ролей к разделам меню (§29): чтение — любой авторизованный (для навигации);
  // изменение — SYSTEM_ADMIN. Это UI-навигация, реальные права — @PreAuthorize на бэкенде.
  roleAccess: () => fetch('/md-api/api/v1/role-access', { headers: authHeaders() }).then(r => handle<RoleAccess[]>(r)),
  saveRoleAccess: (role: string, homeKey: string, navKeys: string[]) =>
    mdPost('role-access', { role, homeKey, navKeys }) as Promise<RoleAccess>,
  // Настройки платформы (§29): чтение по категории; изменение — SYSTEM_ADMIN.
  settings: (category: string) => fetch(`/md-api/api/v1/settings?category=${category}`, { headers: authHeaders() })
    .then(r => handle<PlatformSetting[]>(r)),
  // Публичные настройки (контакты поддержки) — без токена, для страницы входа.
  publicSettings: () => fetch('/md-api/api/v1/settings/public').then(r => handle<PlatformSetting[]>(r)),
  saveSetting: (category: string, settingKey: string, value: string) =>
    mdPost('settings', { category, settingKey, value }) as Promise<PlatformSetting>,
  // Брендинг (§29): изображения логотипа/фона входа. GET публичный (используется в <img src>),
  // загрузка/сброс — SYSTEM_ADMIN. Тексты бренда — обычные настройки категории branding.
  branding: {
    url: (key: 'logo' | 'login_bg') => `/md-api/api/v1/branding/${key}`,
    upload: (key: 'logo' | 'login_bg', file: File) => {
      const form = new FormData();
      form.append('file', file);
      // Content-Type НЕ задаём — браузер сам проставит multipart boundary; authHeaders() даёт только Bearer.
      return fetch(`/md-api/api/v1/branding/${key}`, { method: 'POST', headers: authHeaders(), body: form })
        .then(async r => {
          if (!r.ok) { const p = await r.json().catch(() => null); throw new Error(p?.detail ?? p?.message ?? `Ошибка ${r.status}`); }
          return r.json() as Promise<{ key: string; contentType: string; size: number }>;
        });
    },
    reset: (key: 'logo' | 'login_bg') => fetch(`/md-api/api/v1/branding/${key}`, { method: 'DELETE', headers: authHeaders() })
      .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  },
};

export type RoleAccess = { role: string; homeKey: string; navKeys: string[] };

export type PlatformSetting = {
  id: string;
  category: string;
  settingKey: string;
  valueType: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'ENUM';
  settingValue: string | null;
  options: string | null;
  nameRu: string;
  nameTj: string | null;
  sortOrder: number;
  updatedBy: string | null;
  updatedAt: string;
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

// Пригодность (preflight): проактивная проверка «можно ли оформить такой ПЛ» до создания.
export type EligibilityCheck = { code: string; severity: 'ERROR' | 'WARN'; message: string };
export type Eligibility = { eligible: boolean; checks: EligibilityCheck[] };
export type TypeAvailability = { type: string; available: boolean; reasons: string[] };

// Заявка на путевой лист (driver-initiated): водитель подаёт → диспетчер одобряет/отклоняет.
export type WaybillRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
export type WaybillRequest = {
  id: string;
  organizationRma: string;
  driverRma: string;
  driverName: string | null;
  vehicleRegNumber: string;
  waybillType: string;
  requestedFrom: string | null;
  odometer: number | null;
  communicationType: string | null;
  route: string | null;
  schedule: string | null;
  notes: string | null;
  status: WaybillRequestStatus;
  rejectReason: string | null;
  waybillId: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string;
};

export const wb = {
  list: () => fetch('/wb-api/api/v1/waybills', { headers: authHeaders() }).then(r => handle<Waybill[]>(r)),
  // Доступные типы ПЛ для организации (по лицензии/виду субъекта) — для шага выбора типа.
  availableTypes: (orgRma: string) => fetch(
    `/wb-api/api/v1/waybills/available-types?organizationRma=${encodeURIComponent(orgRma)}`,
    { headers: authHeaders() }).then(r => handle<TypeAvailability[]>(r)),
  // Пригодность выбранной связки (тип+организация+ТС+водитель) — «Доступен/Недоступно + причина».
  preflight: (params: { type: string; organizationRma: string; vehicleRegNumber?: string; driverRma?: string }) => {
    const q = new URLSearchParams({ type: params.type, organizationRma: params.organizationRma });
    if (params.vehicleRegNumber) q.set('vehicleRegNumber', params.vehicleRegNumber);
    if (params.driverRma) q.set('driverRma', params.driverRma);
    return fetch(`/wb-api/api/v1/waybills/preflight?${q.toString()}`, { headers: authHeaders() })
      .then(r => handle<Eligibility>(r));
  },
  // Заявки на путевой лист: водитель (mine/create/cancel), диспетчер (list/edit/approve/reject).
  requests: {
    mine: () => fetch('/wb-api/api/v1/waybill-requests/mine', { headers: authHeaders() }).then(r => handle<WaybillRequest[]>(r)),
    list: (status?: string) => fetch(`/wb-api/api/v1/waybill-requests${status ? `?status=${encodeURIComponent(status)}` : ''}`, { headers: authHeaders() }).then(r => handle<WaybillRequest[]>(r)),
    create: (body: Record<string, unknown>) => fetch('/wb-api/api/v1/waybill-requests', {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => handle<WaybillRequest>(r)),
    cancel: (id: string) => fetch(`/wb-api/api/v1/waybill-requests/${id}/cancel`, { method: 'POST', headers: authHeaders() }).then(r => handle<WaybillRequest>(r)),
    edit: (id: string, body: Record<string, unknown>) => fetch(`/wb-api/api/v1/waybill-requests/${id}`, {
      method: 'PATCH', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => handle<WaybillRequest>(r)),
    approve: (id: string, body?: Record<string, unknown>) => fetch(`/wb-api/api/v1/waybill-requests/${id}/approve`, {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body ?? {}),
    }).then(r => handle<WaybillRequest>(r)),
    reject: (id: string, reason: string) => fetch(`/wb-api/api/v1/waybill-requests/${id}/reject`, {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ reason }),
    }).then(r => handle<WaybillRequest>(r)),
  },
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
  // Уведомления организации (waybill-service).
  notifications: () => fetch('/wb-api/api/v1/notifications', { headers: authHeaders() }).then(r => handle<NotificationItem[]>(r)),
  unreadCount: () => fetch('/wb-api/api/v1/notifications/unread-count', { headers: authHeaders() }).then(r => handle<{ count: number }>(r)),
  markNotifRead: (id: string) => fetch(`/wb-api/api/v1/notifications/${id}/read`, { method: 'POST', headers: authHeaders() })
    .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  markAllNotifRead: () => fetch('/wb-api/api/v1/notifications/read-all', { method: 'POST', headers: authHeaders() })
    .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  // Витрина Neru: действующий ПЛ по госномеру (404 → null).
  neruByPlate: (plate: string) => fetch(`/wb-api/api/v1/neru/active-by-plate?plate=${encodeURIComponent(plate)}`, { headers: authHeaders() })
    .then(async r => r.ok ? (r.json() as Promise<NeruView>) : null),
  // Последняя GPS-позиция ТС (404 → null).
  gpsLast: (vehicleRegNumber: string) => fetch(`/wb-api/api/v1/gps/last?vehicleRegNumber=${encodeURIComponent(vehicleRegNumber)}`, { headers: authHeaders() })
    .then(async r => r.ok ? (r.json() as Promise<GpsPing>) : null),
  // Живой мониторинг: ТС на линии (выданные/активные ПЛ) с последней GPS-координатой (org-скоуп на бэкенде).
  gpsLive: () => fetch('/wb-api/api/v1/gps/live', { headers: authHeaders() }).then(r => handle<LivePosition[]>(r)),
  // Расходы рейса (§12): список/добавление/подтверждение бухгалтером/удаление.
  expenses: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/expenses`, { headers: authHeaders() }).then(r => handle<Expense[]>(r)),
  addExpense: (id: string, body: Record<string, unknown>) => fetch(`/wb-api/api/v1/waybills/${id}/expenses`, {
    method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
  }).then(r => handle<Expense>(r)),
  confirmExpense: (eid: string) => fetch(`/wb-api/api/v1/expenses/${eid}/confirm`, { method: 'POST', headers: authHeaders() }).then(r => handle<Expense>(r)),
  deleteExpense: (eid: string) => fetch(`/wb-api/api/v1/expenses/${eid}`, { method: 'DELETE', headers: authHeaders() })
    .then(async r => { if (!r.ok && r.status !== 204) { const p = await r.json().catch(() => null); throw new Error(p?.detail ?? p?.title ?? `Ошибка ${r.status}`); } }),
};

export type Expense = {
  id: string;
  waybillId: string;
  expenseType: 'PER_DIEM' | 'TOLL' | 'PARKING' | 'LODGING' | 'REPAIR' | 'OTHER';
  amount: number;
  currency: string;
  rate: number | null;
  vat: number | null;
  description: string | null;
  receiptNumber: string | null;
  spentAt: string | null;
  confirmed: boolean;
  confirmedBy: string | null;
  confirmedAt: string | null;
  createdBy: string | null;
  createdAt: string;
};

export type LivePosition = {
  vehicleRegNumber: string; number: string | null; driver: string; status: string;
  lat: number | null; lon: number | null; speedKmh: number | null; recordedAt: string | null;
};
