'use client';

import type { ReactNode } from 'react';
import { FORM_FIELDS, fieldLabel, type FieldMode } from '@/lib/formFields';
import { useT } from '@/lib/i18n';

/**
 * Настраиваемые поля формы (Настройки → Поля водителя / транспорта / сотрудника): рисует
 * незакреплённые поля из общего списка FORM_FIELDS с учётом режима — скрытое не рисуется,
 * обязательное получает «*» и `required`. Закреплённые (системные) поля форма рисует сама.
 * `overrides` — своё поле вместо стандартного (например, выбор закреплённого ТС из списка).
 */
export function ConfigurableFields({ form, modes, value, onChange, overrides, readOnly }: {
  form: string;
  modes: Record<string, FieldMode>;
  value: (k: string) => string;
  onChange: (k: string, v: string) => void;
  overrides?: Record<string, (p: { required: boolean; label: string }) => ReactNode>;
  /** Поле только для чтения (запись из единой платформы — Настройки → Интеграции). */
  readOnly?: (k: string) => boolean;
}) {
  const { t } = useT();
  return (
    <>
      {(FORM_FIELDS[form] ?? []).filter(f => !f.locked && modes[f.key] !== 'hidden').map(f => {
        const disabled = !!readOnly?.(f.key);
        // Закрытое поле не требуем: пользователь его не меняет (сервер тоже не проверяет).
        const required = modes[f.key] === 'required' && !disabled;
        const label = fieldLabel(t, f, required);
        const custom = overrides?.[f.key];
        if (custom) return <div key={f.key} className={f.full ? 'full' : undefined}>{custom({ required, label })}</div>;
        return (
          <div key={f.key} className={f.full ? 'full' : undefined}>
            <label>{label}</label>
            {f.type === 'select' ? (
              <select required={required} disabled={disabled} value={value(f.key)} onChange={e => onChange(f.key, e.target.value)}>
                <option value="">—</option>
                {(f.options ?? []).map(o => <option key={o.v} value={o.v}>{t(o.labelKey)}</option>)}
              </select>
            ) : f.type === 'multiselect' ? (
              <LicenseCategoryPicker
                label={label}
                value={value(f.key)}
                options={(f.options ?? []).map(o => ({ v: o.v, label: t(o.labelKey) }))}
                required={required}
                disabled={disabled}
                onChange={v => onChange(f.key, v)}
              />
            ) : (
              <input required={required} disabled={disabled} type={f.type ?? 'text'} value={value(f.key)}
                placeholder={f.placeholder} pattern={f.pattern} min={f.min} max={f.max} step={f.step}
                onChange={e => onChange(f.key, e.target.value)} />
            )}
          </div>
        );
      })}
    </>
  );
}

/** Multi-select category editor that still round-trips legacy values the parser cannot interpret. */
export function LicenseCategoryPicker({ label, value, options, required, disabled, onChange }: {
  label: string;
  value: string;
  options: { v: string; label: string }[];
  required?: boolean;
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const { t } = useT();
  const selected = parseLicenseCategories(value, options.map(option => option.v));

  if (selected === null) {
    return (
      <>
        <input required={required} disabled={disabled} value={value}
          aria-label={label} placeholder="B,C,D" onChange={e => onChange(e.target.value)} />
        <div className="hint" style={{ marginTop: 4 }}>{t('comp.f.cats.legacy')}</div>
      </>
    );
  }

  const toggle = (code: string) => {
    const next = new Set(selected);
    if (next.has(code)) next.delete(code); else next.add(code);
    onChange(options.filter(option => next.has(option.v)).map(option => option.v).join(','));
  };

  return (
    <>
      {required && (
        <input tabIndex={-1} aria-hidden required value={value} onChange={() => {}}
          style={{ opacity: 0, height: 1, width: 1, padding: 0, border: 0, position: 'absolute' }} />
      )}
      <div role="group" aria-label={label} style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 10px', marginTop: 4 }}>
        {options.map(option => (
          <label key={option.v} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, cursor: disabled ? 'default' : 'pointer' }}>
            <input type="checkbox" checked={selected.includes(option.v)} disabled={disabled}
              onChange={() => toggle(option.v)} style={{ width: 'auto', margin: 0 }} />
            {option.label}
          </label>
        ))}
      </div>
      <div aria-live="polite" style={{ marginTop: 4, fontSize: 12, color: 'var(--muted)' }}>
        {selected.length ? selected.join(', ') : t('comp.f.cats.none')}
      </div>
      <div className="hint" style={{ marginTop: 4 }}>{t('comp.f.cats.hint')}</div>
    </>
  );
}

/** Mirrors the backend parser and greedily recognizes combinations in legacy strings such as "BCD" and "ВСД". */
function parseLicenseCategories(raw: string, options: string[]): string[] | null {
  if (!raw.trim()) return [];
  let normalized = '';
  for (const char of raw.toUpperCase()) {
    const mapped: Record<string, string> = { А: 'A', В: 'B', Б: 'B', С: 'C', Д: 'D', Е: 'E', М: 'M', Т: 'T' };
    if (mapped[char]) normalized += mapped[char];
    else if (' ,.;/-\\\t'.includes(char)) continue;
    else normalized += char;
  }

  const result = new Set<string>();
  const categories = [...options].sort((a, b) => b.length - a.length);
  for (let i = 0; i < normalized.length;) {
    const category = categories.find(option => normalized.startsWith(option, i));
    if (!category) return null;
    result.add(category);
    i += category.length;
  }
  return result.size ? [...result] : null;
}
