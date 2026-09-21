// Клиент API платформы ЭПД РТ (dev: прокси через next.config rewrites)

let authToken = '';
let orgScope = '';

export function setAuthToken(token: string) {
  authToken = token;
}

/** РМА выбранного филиала (переключатель в шапке для администратора компании). Пусто — все филиалы. */
export function setOrgScope(rma: string) {
  orgScope = rma || '';
}

export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  return {
    ...(extra ?? {}),
    ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
    ...(orgScope ? { 'X-Org-Scope': orgScope } : {}),
  };
}

/** Заголовки без сужения области филиалом — для самого переключателя (нужен полный список орг). */
export function authHeadersFullScope(extra?: Record<string, string>): Record<string, string> {
  return { ...(extra ?? {}), ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}) };
}

export type Waybill = {
  id: string;
  number: string | null;
  branchSerial?: number | null;
  branchSerialYear?: number | null;
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
  // Опасные грузы — НЕ отдельный тип, а режим грузового ПЛ (2-Б: отметка «опасный груз» + класс ADR).
  // WB_DANGEROUS остаётся в enum для исторических записей, но из выбора убран.
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
  action: 'CREATE' | 'UPDATE' | 'DELETE' | 'ACCESS' | 'LOGIN' | 'LOGIN_ERROR' | 'LOGOUT' | 'LOGOUT_ERROR';
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
  id: string;
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

// Справочник внешних (зарубежных) городов — /api/v1/external-cities (master-data-service; V60).
// Привязан к стране (countryCode — ISO alpha-2, код классификатора COUNTRY).
export type ExternalCity = {
  id: string;
  countryCode: string;
  nameRu: string;
  nameTj: string | null;
  sortOrder: number;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
};

// Реестр авторизованных мобильных устройств водителей — /api/v1/mobile-devices (master-data-service; V64).
// Перенос legacy-справочника «Телефонҳо» (phone_infos). Все ссылки «мягкие» (по РМА).
// Чтение — мультиарендно (тенант видит свою корхону); upsert/удаление — SYSTEM_ADMIN.
export type MobileDevice = {
  id: string;
  organizationRma: string;
  driverRma: string | null;
  driverName: string;
  brand: string | null;
  model: string;
  authorizedAt: string;
  createdAt?: string;
  updatedAt?: string;
};

// Справочник типов маршрутов — /api/v1/route-types (master-data-service; V63).
// Классифицирует маршрут по дальности сообщения (городской/пригородный/междугородный/…).
// Ключ — числовой code; «мягкая» связь с маршрутом по route.routeTypeCode.
export type RouteType = {
  id: string;
  code: number;
  nameRu: string;
  nameTj: string | null;
  sortOrder: number;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
};

// Эксплуатационная сводка сервисов (Настройки → Производительность/Интеграции/Резервные копии).
export type OpsRuntime = { uptimeMs: number; heapUsedBytes: number; heapMaxBytes: number; processors: number };
export type MdOps = {
  unifiedPlatformMode: string;
  databases: { name: string; size_bytes: number }[];
  runtime: OpsRuntime;
};
export type WbOps = {
  paymentEnabled: boolean;
  aggregatorOpen: boolean;
  signingMode: string;
  kafkaBootstrap: string;
  eventsTopic: string;
  numbering: { type: string; total: number; numbered: number; last_number: string | null }[];
  runtime: OpsRuntime;
};

// Справочник контрагентов (заказчиков) — /api/v1/dictionaries/clients (master-data-service).
export type Client = {
  id: string;
  number: string;
  name: string;
  address: string | null;
  phone: string | null;
  organizationRma: string | null;
};

// Справочник грузов — /api/v1/dictionaries/cargos (master-data-service). Опишем только
// поля, на которые опирается фронт; остальные (если появятся) остаются непрозрачными.
export type Cargo = {
  id: string;
  name: string;
  [key: string]: unknown;
};

// Направление грузовой перевозки (legacy-справочник Direction) — «Самт» бланка 2-Б.
export type Direction = {
  id: number;
  title: string;
  number: number | null;
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
  // Счётчики ТС/водителей/сотрудников по всем организациям одним запросом (без N+1 со страницы «Компания»).
  organizationCounts: () => fetch('/md-api/api/v1/organizations/counts', { headers: authHeaders() })
    .then(r => handle<{ rma: string; vehicles: number; drivers: number; employees: number }[]>(r)),
  // Полный список организаций пользователя без сужения филиалом (для переключателя филиала в шапке).
  myOrganizations: () => fetch('/md-api/api/v1/organizations', { headers: authHeadersFullScope() }).then(r => handle<Record<string, unknown>[]>(r)),
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
  // Справочник городов/районов (перенос из боевого MinTransRT; V54). Опц. фильтр по региону 1..7.
  cities: (region?: number) => fetch(`/md-api/api/v1/cities${region ? `?region=${region}` : ''}`, { headers: authHeaders() }).then(r => handle<Record<string, unknown>[]>(r)),
  // Справочник внешних (зарубежных) городов (V60). Опц. фильтр по стране (ISO alpha-2).
  // Чтение — любой авторизованный; upsert/удаление — SYSTEM_ADMIN.
  externalCities: (country?: string) => fetch(
    `/md-api/api/v1/external-cities${country ? `?country=${encodeURIComponent(country)}` : ''}`,
    { headers: authHeaders() }).then(r => handle<ExternalCity[]>(r)),
  saveExternalCity: (body: Record<string, unknown>) => mdPost('external-cities', body) as Promise<ExternalCity>,
  deleteExternalCity: (id: string) => mdDelete(`external-cities/${id}`),
  // Справочник типов маршрутов (V63). Чтение — любой авторизованный; upsert/удаление — SYSTEM_ADMIN.
  routeTypes: () => fetch('/md-api/api/v1/route-types', { headers: authHeaders() }).then(r => handle<RouteType[]>(r)),
  saveRouteType: (body: Record<string, unknown>) => mdPost('route-types', body) as Promise<RouteType>,
  deleteRouteType: (id: string) => mdDelete(`route-types/${id}`),
  // Реестр мобильных устройств водителей (V64). Чтение мультиарендно: тенант получает только
  // свою корхону (по токену), платформенная роль — все либо конкретной организации (organizationRma).
  // Upsert/удаление — SYSTEM_ADMIN.
  mobileDevices: (organizationRma?: string) => fetch(
    `/md-api/api/v1/mobile-devices${organizationRma ? `?organizationRma=${encodeURIComponent(organizationRma)}` : ''}`,
    { headers: authHeaders() }).then(r => handle<MobileDevice[]>(r)),
  saveMobileDevice: (body: Record<string, unknown>) => mdPost('mobile-devices', body) as Promise<MobileDevice>,
  deleteMobileDevice: (id: string) => mdDelete(`mobile-devices/${id}`),
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
  // Виды/статусы ПЛ как классификаторы (для форм заявки и настроек) — обёртки над classifiers.
  waybillTypes: () => fetch(`/md-api/api/v1/classifiers?category=WAYBILL_TYPE&all=true`, { headers: authHeaders() }).then(r => handle<ClassifierItem[]>(r)),
  waybillStatuses: () => fetch(`/md-api/api/v1/classifiers?category=WAYBILL_STATUS&all=true`, { headers: authHeaders() }).then(r => handle<ClassifierItem[]>(r)),
  // Эксплуатационная сводка master-data (Настройки → Производительность/Резервные копии).
  ops: () => fetch('/md-api/api/v1/ops/overview', { headers: authHeaders() }).then(r => handle<MdOps>(r)),
  // Заказчики (контрагенты) — для «Заказчик» бланка 2-Б и накладной (стороны/груз).
  clients: () => fetch('/md-api/api/v1/dictionaries/clients', { headers: authHeaders() }).then(r => handle<Client[]>(r)),
  saveClient: (body: Record<string, unknown>) => mdPost('dictionaries/clients', body) as Promise<Client>,
  // Грузы — новый справочник (см. контракт GET/POST /api/v1/dictionaries/cargos).
  cargos: () => fetch('/md-api/api/v1/dictionaries/cargos', { headers: authHeaders() }).then(r => handle<Cargo[]>(r)),
  saveCargo: (body: Record<string, unknown>) => mdPost('dictionaries/cargos', body) as Promise<Cargo>,
  // Направления грузовых перевозок (legacy-справочник) — «Самт» бланка 2-Б.
  directions: () => fetch('/md-api/api/v1/legacy-ref/directions', { headers: authHeaders() }).then(r => handle<Direction[]>(r)),
  // Конструктор полей: доп.поля по типу ПЛ.
  fieldDefinitions: (waybillType: string, all = false) => fetch(
    `/md-api/api/v1/field-definitions?waybillType=${waybillType}${all ? '&all=true' : ''}`,
    { headers: authHeaders() }).then(r => handle<FieldDefinition[]>(r)),
  saveFieldDefinition: (body: Record<string, unknown>) => mdPost('field-definitions', body) as Promise<FieldDefinition>,
  deleteFieldDefinition: (id: string) => fetch(`/md-api/api/v1/field-definitions/${id}`, { method: 'DELETE', headers: authHeaders() })
    .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  // Монитор истечения документов. Тенант видит свою организацию (из токена); платформенная
  // роль (SYSTEM_ADMIN/аналитик) обязана указать organizationRma — иначе бэкенд вернёт пусто.
  documentExpiry: (days = 30, organizationRma?: string) => fetch(
    `/md-api/api/v1/document-expiry?days=${days}${organizationRma ? `&organizationRma=${encodeURIComponent(organizationRma)}` : ''}`,
    { headers: authHeaders() }).then(r => handle<ExpiryItem[]>(r)),
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
  // Логины и роли сотрудников перевозчика (Keycloak realm epd). Администратор компании/филиала
  // выдаёт сотруднику вход + одну роль модуля из белого списка; organization_rma форсируется бэкендом.
  orgUsers: {
    provisioningEnabled: () => fetch('/md-api/api/v1/org-users/enabled', { headers: authHeaders() })
      .then(r => handle<boolean>(r)).catch(() => false),
    list: (organizationRma?: string) => fetch(
      `/md-api/api/v1/org-users${organizationRma ? `?organizationRma=${encodeURIComponent(organizationRma)}` : ''}`,
      { headers: authHeaders() }).then(r => handle<OrgUser[]>(r)),
    create: (body: {
      username: string; firstName?: string; lastName?: string; email?: string;
      personRma?: string; organizationRma: string; role: string; password?: string;
    }) => mdPost('org-users', body) as Promise<OrgUser>,
    setEnabled: (id: string, enabled: boolean) => fetch(`/md-api/api/v1/org-users/${id}/enabled`, {
      method: 'PATCH', headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ enabled }),
    }).then(r => handle<OrgUser>(r)),
    setRole: (id: string, role: string, organizationRma: string, username: string) => fetch(`/md-api/api/v1/org-users/${id}/role`, {
      method: 'PATCH', headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ id, role, organizationRma, username }),
    }).then(r => handle<OrgUser>(r)),
    resetPassword: (id: string) => fetch(`/md-api/api/v1/org-users/${id}/reset-password`, {
      method: 'POST', headers: authHeaders() }).then(r => handle<OrgUser>(r)),
    remove: (id: string) => mdDelete(`org-users/${id}`),
  },
  // Учредительные/разрешительные документы организации (скан-копии).
  orgDocuments: {
    list: (rma: string) => fetch(`/md-api/api/v1/organizations/${rma}/documents`, { headers: authHeaders() })
      .then(r => handle<OrgDocument[]>(r)),
    upload: (rma: string, file: File, docType: string, title: string) => {
      const form = new FormData();
      form.append('file', file);
      form.append('docType', docType);
      if (title) form.append('title', title);
      return fetch(`/md-api/api/v1/organizations/${rma}/documents`, { method: 'POST', headers: authHeaders(), body: form })
        .then(async r => {
          if (!r.ok) { const p = await r.json().catch(() => null); throw new Error(p?.detail ?? p?.message ?? `Ошибка ${r.status}`); }
          return r.json() as Promise<OrgDocument>;
        });
    },
    download: async (rma: string, id: string) => {
      const r = await fetch(`/md-api/api/v1/organizations/${rma}/documents/${id}`, { headers: authHeaders() });
      if (!r.ok) throw new Error(`Ошибка ${r.status}`);
      return r.blob();
    },
    review: (rma: string, id: string, action: 'approve' | 'reject', note?: string) =>
      fetch(`/md-api/api/v1/organizations/${rma}/documents/${id}/${action}${note ? `?note=${encodeURIComponent(note)}` : ''}`,
        { method: 'POST', headers: authHeaders() }).then(r => handle<OrgDocument>(r)),
    remove: (rma: string, id: string) => fetch(`/md-api/api/v1/organizations/${rma}/documents/${id}`, { method: 'DELETE', headers: authHeaders() })
      .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  },
};

export type OrgDocument = {
  id: string; organizationRma: string; docType: string; title: string | null;
  fileName: string; contentType: string; sizeBytes: number;
  status: 'PENDING' | 'APPROVED' | 'REJECTED'; reviewNote: string | null;
  uploadedBy: string | null; uploadedAt: string;
  reviewedBy: string | null; reviewedAt: string | null;
};

export type RoleAccess = { role: string; homeKey: string; navKeys: string[] };

/** Акт дорожной проверки: что зафиксировал инспектор при остановке. */
export type Inspection = {
  id: string;
  waybillId: string;
  action: 'PASSED' | 'BLOCKED';
  reasonCode: string | null;
  description: string | null;
  place: string | null;
  lat: number | null;
  lon: number | null;
  protocolNumber: string | null;
  inspectorRma: string;
  inspectorName: string | null;
  createdAt: string;
};

export type InspectionBody = {
  reasonCode?: string | null;
  description?: string | null;
  place?: string | null;
  lat?: number | null;
  lon?: number | null;
  protocolNumber?: string | null;
};

export type OrgUser = {
  id: string; username: string; firstName: string | null; lastName: string | null;
  enabled: boolean; rma: string | null; organizationRma: string | null;
  roles: string[]; temporaryPassword: string | null;
};

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
  // Эксплуатационная сводка waybill-service (Настройки → Производительность/Интеграции/Нумерация).
  ops: () => fetch('/wb-api/api/v1/ops/overview', { headers: authHeaders() }).then(r => handle<WbOps>(r)),
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
  // Подсказка формы топлива из предыдущего ПЛ того же ТС (legacy parking_fuel_left/parking_fuel_give, MIGRATION.md §4.9).
  fuelPrefill: (id: string, fuelType: number) => fetch(`/wb-api/api/v1/waybills/${id}/fuel-prefill?fuelType=${fuelType}`, { headers: authHeaders() }).then(r => handle<FuelPrefill>(r)),
  titles: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/titles`, { headers: authHeaders() }).then(r => handle<Title[]>(r)),
  // Расшифровка медпоказателей титула Т2/Т6 (ИБ-13.1.3). В самом титуле лежит только
  // зашифрованный blob indicatorsEnc, читаемых pressure/pulse/temperature там НЕТ.
  // Доступ — DOCTOR своей организации либо SYSTEM_ADMIN, и КАЖДЫЙ вызов пишется в аудит
  // master-data: дёргать строго по явному действию врача, не пачкой на отрисовке списка.
  medIndicators: (id: string, titleType: string) => fetch(
    `/wb-api/api/v1/waybills/${id}/titles/${encodeURIComponent(titleType)}/indicators`,
    { headers: authHeaders() }).then(r => handle<Record<string, unknown>>(r)),
  history: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/status-history`, { headers: authHeaders() }).then(r => handle<StatusEvent[]>(r)),
  qr: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/qr`, { headers: authHeaders() }).then(r => handle<{ jws: string }>(r)),
  // Серверный печатный бланк PDF (openhtmltopdf + встроенный шрифт + QR). Токен — в заголовке,
  // поэтому не ссылка, а fetch → Blob → открыть/скачать.
  printPdf: async (id: string) => {
    const r = await fetch(`/wb-api/api/v1/waybills/${id}/print.pdf`, { headers: authHeaders() });
    if (!r.ok) throw new Error(`Ошибка ${r.status}`);
    return r.blob();
  },
  // Накладная (приложение к 2-Б) и CMR (к 5Б-БМ) — те же снимки данных, отдельные бланки.
  printAttachmentPdf: async (id: string) => {
    const r = await fetch(`/wb-api/api/v1/waybills/${id}/print-attachment.pdf`, { headers: authHeaders() });
    if (!r.ok) throw new Error(`Ошибка ${r.status}`);
    return r.blob();
  },
  printCmrPdf: async (id: string) => {
    const r = await fetch(`/wb-api/api/v1/waybills/${id}/print-cmr.pdf`, { headers: authHeaders() });
    if (!r.ok) throw new Error(`Ошибка ${r.status}`);
    return r.blob();
  },
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
  // «Сообщить о проблеме»: водитель шлёт сообщение диспетчеру своей организации.
  reportIssue: (body: { issueType: string; message: string; waybillId?: string | null }) =>
    fetch('/wb-api/api/v1/notifications/report', {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => { if (!r.ok && r.status !== 201) return handle(r); }),
  // Витрина Neru: действующий ПЛ по госномеру (404 → null).
  neruByPlate: (plate: string) => fetch(`/wb-api/api/v1/neru/active-by-plate?plate=${encodeURIComponent(plate)}`, { headers: authHeaders() })
    .then(async r => r.ok ? (r.json() as Promise<NeruView>) : null),
  // Дорожный контроль: справочник оснований, акт проверки (с блокировкой и без), история проверок.
  inspectionReasons: () => fetch('/wb-api/api/v1/waybills/inspection-reasons', { headers: authHeaders() })
    .then(r => handle<{ code: string; label: string }[]>(r)),
  inspections: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/inspections`, { headers: authHeaders() })
    .then(r => handle<Inspection[]>(r)),
  inspectPassed: (id: string, body: InspectionBody) => fetch(`/wb-api/api/v1/waybills/${id}/inspection`, {
    method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
  }).then(r => handle<Inspection>(r)),
  blockWaybill: (id: string, body: InspectionBody) => fetch(`/wb-api/api/v1/waybills/${id}/block`, {
    method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
  }).then(r => handle<Waybill>(r)),
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

  // Документы ТС и водителей (прикрепление → одобрение). subject: 'vehicles' | 'drivers'.
  subjectDocuments: {
    list: (subject: 'vehicles' | 'drivers', key: string) =>
      fetch(`/md-api/api/v1/${subject}/${encodeURIComponent(key)}/documents`, { headers: authHeaders() }).then(r => handle<SubjectDocument[]>(r)),
    upload: (subject: 'vehicles' | 'drivers', key: string, file: File, docType: string, title: string, validTo: string) => {
      const form = new FormData();
      form.append('file', file);
      form.append('docType', docType);
      if (title) form.append('title', title);
      if (validTo) form.append('validTo', validTo);
      return fetch(`/md-api/api/v1/${subject}/${encodeURIComponent(key)}/documents`, { method: 'POST', headers: authHeaders(), body: form })
        .then(async r => { if (!r.ok) { const p = await r.json().catch(() => null); throw new Error(p?.detail ?? p?.message ?? `Ошибка ${r.status}`); } return r.json() as Promise<SubjectDocument>; });
    },
    download: async (subject: 'vehicles' | 'drivers', key: string, id: string) => {
      const r = await fetch(`/md-api/api/v1/${subject}/${encodeURIComponent(key)}/documents/${id}`, { headers: authHeaders() });
      if (!r.ok) throw new Error(`Ошибка ${r.status}`);
      return r.blob();
    },
    review: (subject: 'vehicles' | 'drivers', key: string, id: string, action: 'approve' | 'reject', note?: string) =>
      fetch(`/md-api/api/v1/${subject}/${encodeURIComponent(key)}/documents/${id}/${action}${note ? `?note=${encodeURIComponent(note)}` : ''}`,
        { method: 'POST', headers: authHeaders() }).then(r => handle<SubjectDocument>(r)),
    remove: (subject: 'vehicles' | 'drivers', key: string, id: string) =>
      fetch(`/md-api/api/v1/${subject}/${encodeURIComponent(key)}/documents/${id}`, { method: 'DELETE', headers: authHeaders() })
        .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  },

  // Кабинет пункта выдачи топлива (роль FUEL_STATION).
  fuelStation: {
    list: () => fetch('/wb-api/api/v1/fuel-station/waybills', { headers: authHeaders() }).then(r => handle<FuelStationWaybill[]>(r)),
    fuel: (id: string) => fetch(`/wb-api/api/v1/fuel-station/waybills/${id}/fuel`, { headers: authHeaders() }).then(r => handle<FuelLine[]>(r)),
    record: (id: string, body: Record<string, unknown>) => fetch(`/wb-api/api/v1/fuel-station/waybills/${id}/fuel`, {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => handle<unknown>(r)),
  },

  // Справки пассажирам (маълумотнома).
  malumotnoma: {
    list: () => fetch('/wb-api/api/v1/malumotnomas', { headers: authHeaders() }).then(r => handle<Malumotnoma[]>(r)),
    routes: () => fetch('/wb-api/api/v1/malumotnomas/routes', { headers: authHeaders() }).then(r => handle<MalumotnomaRoute[]>(r)),
    create: (body: Record<string, unknown>) => fetch('/wb-api/api/v1/malumotnomas', {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => handle<Malumotnoma>(r)),
    remove: (id: string) => fetch(`/wb-api/api/v1/malumotnomas/${id}`, { method: 'DELETE', headers: authHeaders() })
      .then(async r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
    report: (from: string, to: string, issuerRma?: string) => fetch(
      `/wb-api/api/v1/malumotnomas/report?from=${from}&to=${to}${issuerRma ? `&issuerRma=${encodeURIComponent(issuerRma)}` : ''}`,
      { headers: authHeaders() }).then(r => handle<MalumotnomaReport>(r)),
  },
  // Плановые показатели перевозок (для сводного отчёта Минтранса).
  plans: {
    list: (kind = 'PASSENGER') => fetch(`/wb-api/api/v1/waybill-plans?kind=${kind}`, { headers: authHeaders() }).then(r => handle<WaybillPlan[]>(r)),
    upsert: (body: Record<string, unknown>) => fetch('/wb-api/api/v1/waybill-plans', {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => handle<WaybillPlan>(r)),
    remove: (id: string) => fetch(`/wb-api/api/v1/waybill-plans/${id}`, { method: 'DELETE', headers: authHeaders() })
      .then(async r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  },
  // Тарифицированные маршруты справок (правит SYSTEM_ADMIN).
  malumotnomaRoutes: {
    all: () => fetch('/wb-api/api/v1/malumotnomas/routes?all=true', { headers: authHeaders() }).then(r => handle<MalumotnomaRoute[]>(r)),
    create: (body: Record<string, unknown>) => fetch('/wb-api/api/v1/malumotnomas/routes', {
      method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => handle<MalumotnomaRoute>(r)),
    update: (id: string, body: Record<string, unknown>) => fetch(`/wb-api/api/v1/malumotnomas/routes/${id}`, {
      method: 'PUT', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body),
    }).then(r => handle<MalumotnomaRoute>(r)),
    remove: (id: string) => fetch(`/wb-api/api/v1/malumotnomas/routes/${id}`, { method: 'DELETE', headers: authHeaders() })
      .then(async r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  },
  // Сводный отчёт «Количество путевых листов» (§6.3, 19 счётчиков + 2 доп. счётчика накладных).
  regionalCount: (bill: string, from: string, to: string, typeCompany?: string) => fetch(
    `/wb-api/api/v1/reports/regional-count?bill=${bill}&from=${from}&to=${to}${typeCompany ? `&typeCompany=${typeCompany}` : ''}`,
    { headers: authHeaders() }).then(r => handle<RegionalCount>(r)),
  // Тренд пассажирооборота (млн пасс-км) по месяцам — KPI дашборда (перенос Ebus\PassengerVolumeController).
  // Активность ТС / водителей за период — число ПЛ выбранных видов по госномеру / РМА (MIGRATION.md 8.5/8.6,
  // фильтры legacy-реестров «активные / без ПЛ / ровно N за период»).
  activity: (by: 'VEHICLE' | 'DRIVER', from: string, to: string, types?: string[], organizationRma?: string) => fetch(
    `/wb-api/api/v1/reports/activity?by=${by}&from=${from}&to=${to}${types && types.length ? `&types=${types.join(',')}` : ''}${organizationRma ? `&organizationRma=${organizationRma}` : ''}`,
    { headers: authHeaders() }).then(r => handle<{ key: string; waybills: number }[]>(r)),
  // type — WB_BUS | WB_TROLLEYBUS (пусто = оба); каждая точка несёт и число выписанных ПЛ (legacy BillCountsController).
  passengerVolumeTrend: (months = 7, type?: string) => fetch(
    `/wb-api/api/v1/reports/passenger-volume-trend?months=${months}${type ? `&type=${type}` : ''}`, { headers: authHeaders() })
    .then(r => handle<PassengerVolumeTrend>(r)),
  // Журналы предрейсового контроля (Дафтари қайди механик / духтӯр, типы 13/14).
  mechanicJournal: (from: string, to: string) => fetch(
    `/wb-api/api/v1/reports/journal/mechanic?from=${from}&to=${to}`, { headers: authHeaders() })
    .then(r => handle<MechanicJournal>(r)),
  doctorJournal: (from: string, to: string) => fetch(
    `/wb-api/api/v1/reports/journal/doctor?from=${from}&to=${to}`, { headers: authHeaders() })
    .then(r => handle<DoctorJournal>(r)),

  // Вложения к конкретному путевому листу: скан-копии сопроводительных документов рейса
  // (CMR / международная накладная, ТТН, весовой сертификат, скан дозвола, фото груза).
  attachments: {
    list: (id: string) => fetch(`/wb-api/api/v1/waybills/${id}/attachments`, { headers: authHeaders() })
      .then(r => handle<WaybillAttachment[]>(r)),
    upload: (id: string, file: File, docType: string, title: string) => {
      const form = new FormData();
      form.append('file', file);
      form.append('docType', docType);
      if (title) form.append('title', title);
      return fetch(`/wb-api/api/v1/waybills/${id}/attachments`, { method: 'POST', headers: authHeaders(), body: form })
        .then(async r => { if (!r.ok) { const p = await r.json().catch(() => null); throw new Error(p?.detail ?? p?.message ?? `Ошибка ${r.status}`); } return r.json() as Promise<WaybillAttachment>; });
    },
    download: async (id: string, attachmentId: string) => {
      const r = await fetch(`/wb-api/api/v1/waybills/${id}/attachments/${attachmentId}`, { headers: authHeaders() });
      if (!r.ok) throw new Error(`Ошибка ${r.status}`);
      return r.blob();
    },
    remove: (id: string, attachmentId: string) => fetch(`/wb-api/api/v1/waybills/${id}/attachments/${attachmentId}`, { method: 'DELETE', headers: authHeaders() })
      .then(r => { if (!r.ok && r.status !== 204) throw new Error(`Ошибка ${r.status}`); }),
  },
};

export type WaybillAttachment = {
  id: string; waybillId: string; docType: string; title: string | null;
  fileName: string; contentType: string; sizeBytes: number;
  uploadedBy: string | null; uploadedAt: string;
};

export type WaybillPlan = {
  id: string; organizationRma: string | null; regionId: number | null;
  planYear: number; planMonth?: number | null; planKind: string; volumeThousand: number; rotationMillion: number; note: string | null;
};

export type RegionalCounts = {
  issuedMonth: number; issuedPrevMonth: number; issuedMonthDelta: number;
  issuedYtd: number; issuedYtdPrev: number; issuedYtdDelta: number;
  processedMonth: number; processedPrevMonth: number; processedMonthDelta: number;
  processedYtd: number; processedYtdPrev: number; processedYtdDelta: number;
  unprocessedYtd: number; vehiclesYtd: number; vehiclesMonth: number; vehiclesPrevMonth: number;
  vehiclesMonthPrevYear: number; vehiclesMonthDelta: number; vehiclesYoYDelta: number;
  cargoWaybillsTotal: number; cargoWaybillsWithConsignment: number;
};
export type RegionalCount = {
  billKind: string; from: string; to: string; year: number; prevYear: number;
  regions: { title: string; regionId: number | null; totals: RegionalCounts;
    cities: { title: string; totals: RegionalCounts;
      companies: { title: string; organizationRma: string; totals: RegionalCounts }[] }[] }[];
  totals: RegionalCounts;
};

export type PassengerVolumeTrend = {
  months: number;
  types?: string[];
  points: { month: string; turnoverMillion: number; waybills: number }[];
};

type InspMark = {
  verdict: string; employeeName: string; employeeRma: string;
  signedAt: string; fingerprint: string; details: string;
};
export type MechanicJournal = {
  from: string; to: string; organizationRma: string | null;
  rows: { number: string; date: string; vehicle: string; driver: string; odometerExit: number | null; control: InspMark }[];
};
export type DoctorJournal = {
  from: string; to: string; organizationRma: string | null;
  rows: { number: string; date: string; vehicle: string; driver: string; preTrip: InspMark | null; postTrip: InspMark | null }[];
};

export type SubjectDocument = {
  id: string; subjectType: string; subjectKey: string; docType: string; title: string | null;
  validTo: string | null; fileName: string; contentType: string; sizeBytes: number;
  status: 'PENDING' | 'APPROVED' | 'REJECTED'; reviewNote: string | null;
  uploadedBy: string | null; uploadedAt: string; reviewedBy: string | null; reviewedAt: string | null;
};

// Автоподстановка топливной строки из предыдущего ПЛ того же ТС (MIGRATION.md §4.9).
export type FuelPrefill = {
  found: boolean; fuelType: number;
  remainBeforeExit: number | null; beGiven: number | null;
  sourceWaybillId: string | null; sourceWaybillNumber: string | null; sourceAt: string | null;
};
export type FuelStationWaybill = {
  id: string; number: string | null; type: string; status: string;
  vehicleRegNumber: string; vehicleBrand: string; driver: string; route: string | null;
  totalGiven: number; fuelRecords: number;
};
export type FuelLine = {
  id: string; fuelType: number; fuelName: string;
  fuelGiven: number | null; remainBeforeExit: number | null; remainEntry: number | null;
  additionalGiven: number | null; returned: number | null;
  // Перенос 1-в-1 (MIGRATION.md §5.6): надбавка при t° ниже 0 (legacy coef_below_0) и норма к выдаче (be_given).
  coefBelow0: number | null; beGiven: number | null;
  at: string | null;
};

export type MalumotnomaRoute = {
  id: string; name: string; distanceKm: number;
  carPrice: number; mbusPrice: number; busPrice: number; active: boolean;
};
export type Malumotnoma = {
  id: string; fio: string; transportTypeId: number; age: number;
  organizationRma: string | null; issuerRma: string | null; issuerName: string | null;
  price: number; routeSummary: string | null; createdAt: string;
  lines: { id: string; route: MalumotnomaRoute; roundTrip: boolean }[];
};
export type MalumotnomaReport = {
  from: string; to: string; count: number; total: number;
  groups: {
    issuerRma: string; issuerName: string | null; count: number; amount: number;
    items: {
      id: string; fio: string; transportType: string; privileged: boolean;
      issuedAt: string; routes: string; price: number; issuerName: string | null; updaterName: string | null;
    }[];
  }[];
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
  waybillType: string | null;
  organizationRma: string | null; organizationName: string | null;
  lat: number | null; lon: number | null; speedKmh: number | null; recordedAt: string | null;
};
