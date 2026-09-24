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
