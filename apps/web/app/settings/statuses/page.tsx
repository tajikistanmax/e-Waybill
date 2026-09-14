'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { md, STATUS_LABELS } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../../icons';

/** Статусная модель ПЛ по этапам жизненного цикла. Сама машина переходов — в коде
 *  (waybill-service); здесь редактируются только НАЗВАНИЯ статусов (RU/TJ) —
 *  классификатор WAYBILL_STATUS, применяются во всём приложении через tStatus. */
const STAGES: { key: string; statuses: string[] }[] = [
  { key: 'create', statuses: ['DRAFT', 'CREATED'] },
  { key: 'exam', statuses: ['MED_REJECTED', 'TECH_REJECTED'] },
  { key: 'ready', statuses: ['AWAITING_PAYMENT', 'PAID', 'READY'] },
  { key: 'trip', statuses: ['ISSUED', 'ACTIVE', 'RETURNED'] },
  { key: 'done', statuses: ['COMPLETED', 'ARCHIVED'] },
  { key: 'exc', statuses: ['CANCELLED', 'EXPIRED', 'BLOCKED'] },
];
const ORDER = STAGES.flatMap(st => st.statuses);

const OPEN = ['CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE'];
const TERMINAL = ['COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED'];

export default function StatusesSettingsPage() {
  const { t, tStatus } = useT();
  const { roles } = useAuth();
  const canEdit = roles.includes('SYSTEM_ADMIN');
  const [names, setNames] = useState<Record<string, { ru: string; tj: string }>>({});
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => {
    md.waybillStatuses()
      .then(list => setNames(Object.fromEntries(list.map(c => [c.code, { ru: c.nameRu, tj: c.nameTj ?? '' }]))))
      .catch(() => { /* нет связи — плейсхолдеры покажут текущие названия */ });
  }, []);

  const set = (code: string, field: 'ru' | 'tj', v: string) =>
    setNames(s => ({ ...s, [code]: { ...(s[code] ?? { ru: '', tj: '' }), [field]: v } }));

  async function save(code: string) {
    setBusy(code); setErr(''); setMsg('');
    try {
      const n = names[code] ?? { ru: '', tj: '' };
      if (!n.ru.trim()) { setErr(t('settypes.needname')); setBusy(''); return; }
      await md.saveClassifier({
        category: 'WAYBILL_STATUS', code,
        nameRu: n.ru.trim(), nameTj: n.tj.trim() || null,
        sortOrder: ORDER.indexOf(code) + 1, active: true,
      });
      setMsg(t('settypes.saved'));
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(''); }
  }

  const nature = (s: string) =>
    OPEN.includes(s) ? { key: 'stat.n.open', cls: 'green' }
    : TERMINAL.includes(s) ? { key: 'stat.n.terminal', cls: 'gray' }
    : { key: 'stat.n.transient', cls: 'amber' };

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.statuses')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('stat.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 14 }}>{t('stat.note')}{canEdit ? ` ${t('stat.editnote')}` : ''}</div>
      {msg && <div className="success" style={{ marginBottom: 12 }}>{msg}</div>}
      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(430px, 1fr))', gap: 14 }}>
        {STAGES.map((stage, i) => (
          <div key={stage.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <span style={{ width: 24, height: 24, borderRadius: 7, background: 'var(--blue-050)', color: 'var(--blue-700)', display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 12 }}>{i + 1}</span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`stat.stage.${stage.key}`)}</div>
            </div>
            <table>
              <tbody>
                {stage.statuses.map(sc => {
                  const color = STATUS_LABELS[sc]?.color ?? 'gray';
                  const nat = nature(sc);
                  return (
                    <tr key={sc}>
                      <td style={{ whiteSpace: 'nowrap' }}>
                        <span className={`badge ${color}`}>{tStatus(sc)}</span>
                        <div style={{ marginTop: 3 }}><span className={`badge ${nat.cls}`} style={{ fontSize: 10.5 }}>{t(nat.key)}</span></div>
                      </td>
                      {canEdit ? (
                        <>
                          <td><input value={names[sc]?.ru ?? ''} placeholder={tStatus(sc)}
                            onChange={e => set(sc, 'ru', e.target.value)} style={{ width: 130 }} /></td>
                          <td><input value={names[sc]?.tj ?? ''} placeholder="TJ"
                            onChange={e => set(sc, 'tj', e.target.value)} style={{ width: 130 }} /></td>
                          <td><button className="btn" style={{ padding: '5px 10px', fontSize: 12 }} disabled={busy === sc} onClick={() => save(sc)}>
                            {busy === sc ? '…' : t('fleet.save')}
                          </button></td>
                        </>
                      ) : (
                        <td style={{ textAlign: 'right', color: 'var(--faint)', fontFamily: 'var(--mono)', fontSize: 11.5 }}>{sc}</td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ))}
      </div>
    </>
  );
}
