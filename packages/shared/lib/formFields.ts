import { useEffect, useState } from 'react';
import { md } from './api';

/**
 * Поля форм, которыми администратор управляет из настроек без изменения кода
 * (Настройки → Поля компании (организации); решение владельца 24.09.2026). Настройка платформы категории
 * `forms`, ключ = имя формы, значение — JSON {"поле":"hidden|required|show"}.
 *
 * Список полей зеркалит серверный FormFieldPolicy (master-data): сервер проверяет
 * обязательность при сохранении, форма — прячет поле и ставит `required`.
 */

export type FieldMode = 'show' | 'required' | 'hidden';

/** Поле формы: ключ в запросе, ключ подписи i18n; locked — системное (всегда обязательно);
 *  noRequired — поле, которое нельзя сделать обязательным (отметка «да/нет», закрепление ТС).
 *  type/options/… — как рисовать поле в формах, которые строятся по этому списку
 *  (водитель, ТС, сотрудник); numeric — значение отправляется числом. */
export type FormFieldDef = {
  key: string; labelKey: string; locked?: boolean; noRequired?: boolean;
  type?: 'text' | 'number' | 'date' | 'email' | 'select';
  options?: { v: string; labelKey: string }[];
  numeric?: boolean; placeholder?: string; pattern?: string; min?: number; max?: number; step?: string; full?: boolean;
};

const opts = (prefix: string, n: number) => Array.from({ length: n }, (_, i) => ({ v: String(i + 1), labelKey: `${prefix}${i + 1}` }));
const date = (key: string, labelKey: string): FormFieldDef => ({ key, labelKey, type: 'date' });
const num = (key: string, labelKey: string, extra: Partial<FormFieldDef> = {}): FormFieldDef => ({ key, labelKey, type: 'number', numeric: true, ...extra });

export const FORM_FIELDS: Record<string, FormFieldDef[]> = {
  organization: [
    { key: 'rma', labelKey: 'comp.f.rmainn', locked: true },
    { key: 'name', labelKey: 'comp.f.name_req', locked: true },
    { key: 'kpp', labelKey: 'comp.f.kpp' },
    { key: 'internalNumber', labelKey: 'org.f.internalnumber' },
    { key: 'typeCompany', labelKey: 'comp.f.typecompany' },
    { key: 'regionId', labelKey: 'f.region' },
    { key: 'cityName', labelKey: 'comp.f.city' },
    { key: 'address', labelKey: 'col.address' },
    { key: 'phone', labelKey: 'col.phone' },
    { key: 'email', labelKey: 'comp.f.email' },
    { key: 'nameHead', labelKey: 'comp.f.head' },
    { key: 'bank', labelKey: 'comp.f.bank' },
    { key: 'licenseFrom', labelKey: 'comp.f.licfrom' },
    { key: 'licenseTo', labelKey: 'comp.f.licto' },
    { key: 'carrierLicenseNumber', labelKey: 'dt.carrierlicnum' },
    { key: 'percentIncome', labelKey: 'cf.incomeshare' },
    { key: 'cat1', labelKey: 'cf.cat1' },
    { key: 'cat2', labelKey: 'cf.cat2' },
    { key: 'cat3', labelKey: 'cf.cat3' },
    { key: 'ownership', labelKey: 'org.f.ownership' },
    { key: 'registrationCertNumber', labelKey: 'org.f.regcert' },
    { key: 'extractNumber', labelKey: 'org.f.extract' },
    { key: 'vatCertNumber', labelKey: 'org.f.vatcert' },
    { key: 'planPassVolume', labelKey: 'org.f.planvolume' },
    { key: 'planPassTraffic', labelKey: 'org.f.plantraffic' },
    { key: 'latitude', labelKey: 'org.f.latitude' },
    { key: 'longitude', labelKey: 'org.f.longitude' },
    { key: 'mapPoints', labelKey: 'org.f.mappoints' },
    { key: 'giveFuel', labelKey: 'org.f.givefuel', noRequired: true },
    { key: 'allowedWaybillTypes', labelKey: 'cf.allowedtypes' },
  ],
  // Водитель, ТС, сотрудник — полный состав карточки (разделы «Компания» и «Парк» строят формы
  // по этому списку, поэтому поля там одинаковые и при изменении записи ничего не затирается).
  driver: [
    { key: 'rma', labelKey: 'comp.f.rmainn', locked: true, pattern: '\\d{9,10}' },
    { key: 'fullName', labelKey: 'comp.f.fio_req', locked: true, placeholder: 'Иванов Иван Иванович' },
    { key: 'tabNumber', labelKey: 'f.tab' },
    date('birthDate', 'fleet.f.birth'),
    num('experienceYears', 'fleet.f.experience', { min: 0, max: 80 }),
    { key: 'licenseNumber', labelKey: 'comp.f.licnum', placeholder: '77 01 123456' },
    { key: 'licenseCategories', labelKey: 'comp.f.cats' },
    date('licenseValidTo', 'comp.f.licvalid'),
    num('degree', 'comp.f.degree', { min: 1, max: 3 }),
    { key: 'medCertNumber', labelKey: 'comp.f.medcertnum' },
    date('medCertValidTo', 'col.medto'),
    { key: 'medRestrictions', labelKey: 'fleet.f.medrestr' },
    date('safetyCourseValidTo', 'comp.f.safetyto'),
    { key: 'safetyCourseNumber', labelKey: 'cf.safetynum20' },
    date('adrCertValidTo', 'fleet.f.adrcert'),
    { key: 'phone', labelKey: 'col.phone' },
    { key: 'passport', labelKey: 'drv.f.passport' },
    { key: 'address', labelKey: 'col.address' },
    { key: 'email', labelKey: 'drv.f.email', type: 'email' },
    { key: 'powerAttorney', labelKey: 'drv.f.powerattorney' },
    date('visaValidTo', 'drv.f.visato'),
    { key: 'contractNumber', labelKey: 'drv.f.contractnum' },
    date('contractValidTo', 'drv.f.contractto'),
    { key: 'assignedVehicleId', labelKey: 'drv.f.assignedveh', noRequired: true },
  ],
  vehicle: [
    { key: 'registrationNumber', labelKey: 'comp.f.regnum_req', locked: true },
    { key: 'transportType', labelKey: 'comp.f.vehtype_req', locked: true, type: 'select', numeric: true, options: opts('tt.', 6) },
    { key: 'brand', labelKey: 'tech.f.brandmodel', placeholder: 'КАМАЗ 65115' },
    { key: 'vincode', labelKey: 'fleet.f.vin' },
    { key: 'fuelType', labelKey: 'fleet.f.fueltype', type: 'select', numeric: true, options: opts('fuel.type.', 5) },
    num('enginePower', 'fleet.f.enginepower', { min: 0, max: 3000 }),
    num('yearManufacture', 'comp.f.year', { min: 1950, max: 2100 }),
    { key: 'parkingNumber', labelKey: 'comp.f.parking', pattern: '\\d{4}' },
    num('capacity', 'comp.f.capacity', { min: 0 }),
    num('carrying', 'comp.f.carrying', { min: 0, step: '0.01' }),
    num('odometer', 'comp.f.odometerkm', { min: 0 }),
    date('techInspectionValidTo', 'col.techto'),
    { key: 'techInspectionNumber', labelKey: 'veh.f.techinspnum' },
    { key: 'techPassportNumber', labelKey: 'veh.f.techpassnum' },
    { key: 'certificateNumber', labelKey: 'veh.f.certnum' },
    date('controlCardValidTo', 'comp.f.controlcardto'),
    { key: 'controlCardNumber', labelKey: 'cf.controlcardnum' },
    { key: 'intlCertificateNumber', labelKey: 'cf.intlcertnum' },
    { key: 'intlControlCardNumber', labelKey: 'veh.f.intlcardnum' },
    date('intlControlCardValidTo', 'veh.f.intlcardto'),
    date('insuranceValidTo', 'cf.insosago'),
    date('adrApprovalValidTo', 'dt.adrto'),
    num('airConditioner', 'veh.f.aircond', { min: 0, max: 100 }),
    { key: 'trailer1Number', labelKey: 'veh.f.tr1num', placeholder: '0101TJ01' },
    { key: 'trailer1Brand', labelKey: 'veh.f.tr1brand' },
    num('trailer1Carrying', 'veh.f.tr1carrying', { min: 0, step: '0.01' }),
    num('trailer1Weight', 'veh.f.tr1weight', { min: 0, step: '0.01' }),
    { key: 'trailer2Number', labelKey: 'veh.f.tr2num', placeholder: '0101TJ01' },
    { key: 'trailer2Brand', labelKey: 'veh.f.tr2brand' },
    num('trailer2Carrying', 'veh.f.tr2carrying', { min: 0, step: '0.01' }),
    num('trailer2Weight', 'veh.f.tr2weight', { min: 0, step: '0.01' }),
  ],
  employee: [
    { key: 'rma', labelKey: 'comp.f.rmainn', locked: true, pattern: '\\d{9,10}' },
    { key: 'name', labelKey: 'comp.f.fio_req', locked: true, placeholder: 'Петров Пётр Петрович' },
    { key: 'type', labelKey: 'comp.f.position_req', locked: true, type: 'select', numeric: true, options: opts('fleet.emp.', 5) },
    { key: 'tabNumber', labelKey: 'f.tab' },
    { key: 'phone', labelKey: 'col.phone' },
    { key: 'address', labelKey: 'col.address', placeholder: 'г. Душанбе, ул. …', full: true },
    { key: 'certNumber', labelKey: 'emp.f.certnum' },
    date('certValidTo', 'emp.f.certto'),
  ],
};

/** Ключи всех полей формы — для предзаполнения при изменении записи и отправки целиком. */
export const formKeys = (form: string) => (FORM_FIELDS[form] ?? []).map(f => f.key);
/** Ключи полей, значения которых отправляются числом. */
export const numericKeys = (form: string) => (FORM_FIELDS[form] ?? []).filter(f => f.numeric).map(f => f.key);
/** Подпись поля: без «*» из перевода; «*» добавляется, если поле обязательно. */
export function fieldLabel(t: (k: string) => string, f: FormFieldDef, required: boolean) {
  const s = t(f.labelKey).replace(/\s*\*\s*$/, '');
  return required ? `${s} *` : s;
}

export const FORMS_CATEGORY = 'forms';

/** Режимы полей по умолчанию: всё показано, системные поля обязательны. */
export function defaultFieldModes(form: string): Record<string, FieldMode> {
  const out: Record<string, FieldMode> = {};
  for (const f of FORM_FIELDS[form] ?? []) out[f.key] = f.locked ? 'required' : 'show';
  return out;
}

/** Разбор значения настройки. Снисходительный: мусор и неизвестные ключи пропускаются,
 *  системные поля остаются обязательными — как и на сервере. */
export function parseFieldModes(form: string, raw: string | null | undefined): Record<string, FieldMode> {
  const out = defaultFieldModes(form);
  if (!raw || !raw.trim()) return out;
  let obj: unknown;
  try { obj = JSON.parse(raw); } catch { return out; }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return out;
  for (const f of FORM_FIELDS[form] ?? []) {
    if (f.locked) continue;
    const v = (obj as Record<string, unknown>)[f.key];
    if (v === 'show' || v === 'hidden' || (v === 'required' && !f.noRequired)) out[f.key] = v;
  }
  return out;
}

/** В настройку пишем только отличия от умолчания — короче и не ломается при добавлении полей. */
export function serializeFieldModes(form: string, modes: Record<string, FieldMode>): string {
  const diff: Record<string, FieldMode> = {};
  for (const f of FORM_FIELDS[form] ?? []) {
    if (f.locked) continue;
    const m = modes[f.key];
    if (m && m !== 'show') diff[f.key] = m;
  }
  return Object.keys(diff).length ? JSON.stringify(diff) : '';
}

/**
 * Режимы полей формы для рендера: `show(k)` — рисовать ли поле, `req(k)` — обязательно ли.
 * Пока настройка грузится (или недоступна) — поведение по умолчанию: форма не пропадает.
 */
export function useFormFieldModes(form: string) {
  const [modes, setModes] = useState<Record<string, FieldMode>>(() => defaultFieldModes(form));
  useEffect(() => {
    let alive = true;
    md.settings(FORMS_CATEGORY)
      .then(list => {
        const s = list.find(x => x.settingKey === form);
        if (alive) setModes(parseFieldModes(form, s?.settingValue));
      })
      .catch(() => { /* по умолчанию */ });
    return () => { alive = false; };
  }, [form]);
  return {
    modes,
    show: (k: string) => modes[k] !== 'hidden',
    req: (k: string) => modes[k] === 'required',
  };
}
