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
 *  noRequired — отметка «да/нет», обязательной быть не может. */
export type FormFieldDef = { key: string; labelKey: string; locked?: boolean; noRequired?: boolean };

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
};

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
