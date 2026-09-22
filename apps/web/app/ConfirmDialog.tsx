'use client';

import { useT } from '@/lib/i18n';

/** Одно изменение для окна подтверждения: что меняем и с какого значения на какое. */
export type ChangeLine = { label: string; from: string; to: string };

/**
 * Окно подтверждения изменения настроек (замечание владельца 22.09: «можно случайно что-то
 * отключить — нужна кнопка сохранить и предупреждение о последствиях»).
 * Показывает список правок «было → стало» и текст последствий, который передаёт вызывающая страница.
 */
export function ConfirmDialog({ title, changes, consequences, busy, onConfirm, onCancel, confirmLabel, danger }: {
  title: string;
  changes: ChangeLine[];
  consequences: string[];
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  confirmLabel?: string;
  danger?: boolean;
}) {
  const { t } = useT();
  return (
    <div onClick={onCancel} style={{ position: 'fixed', inset: 0, background: 'rgba(15,32,60,.45)', zIndex: 80, display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '6vh 16px', overflowY: 'auto' }}>
      <div onClick={e => e.stopPropagation()} className="card" style={{ maxWidth: 620, width: '100%', margin: 0 }}>
        <div className="card-h">
          <h2>{title}</h2>
          <button type="button" className="btn secondary" style={{ marginLeft: 'auto' }} onClick={onCancel}>✕</button>
        </div>

        {changes.length > 0 && (
          <table style={{ marginBottom: 14 }}>
            <thead><tr><th>{t('cfm.what')}</th><th>{t('cfm.was')}</th><th>{t('cfm.becomes')}</th></tr></thead>
            <tbody>
              {changes.map((c, i) => (
                <tr key={i}>
                  <td style={{ fontWeight: 600, color: 'var(--ink)' }}>{c.label}</td>
                  <td style={{ color: 'var(--muted)' }}>{c.from || '—'}</td>
                  <td style={{ fontWeight: 600 }}>{c.to || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div style={{ background: 'var(--amber-050, #fef9e7)', border: '1px solid var(--line)', borderRadius: 10, padding: 12, marginBottom: 14 }}>
          <div style={{ fontWeight: 700, fontSize: 13, marginBottom: 6 }}>{t('cfm.consequences')}</div>
          <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13, color: 'var(--ink-soft)' }}>
            {consequences.map((c, i) => <li key={i} style={{ marginBottom: 4 }}>{c}</li>)}
          </ul>
        </div>

        <div style={{ display: 'flex', gap: 10 }}>
          <button type="button" className="btn" style={danger ? { background: 'var(--red)' } : undefined} disabled={busy} onClick={onConfirm}>
            {busy ? '…' : (confirmLabel ?? t('cfm.apply'))}
          </button>
          <button type="button" className="btn secondary" onClick={onCancel}>{t('btn.cancel')}</button>
        </div>
      </div>
    </div>
  );
}
