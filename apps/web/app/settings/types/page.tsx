'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { md } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../../icons';

/** Структурные параметры типов ПЛ (из enum WaybillType) — read-only. Редактируется только НАЗВАНИЕ
 *  (классификатор WAYBILL_TYPE), которое применяется во всём приложении (tType). */
const TYPES = [
  { code: 'WB_CAR', form: '3-С', num: '01', days: 7, pax: false, intl: false },
  { code: 'WB_TAXI', form: '3-С такси', num: '02', days: 7, pax: true, intl: false },
  { code: 'WB_MINIBUS', form: '1-А', num: '03', days: 4, pax: true, intl: false },
  { code: 'WB_BUS', form: 'Т(1-АД)', num: '04', days: 1, pax: true, intl: false },
  { code: 'WB_TROLLEYBUS', form: 'Т(1-АД)', num: '05', days: 1, pax: true, intl: false },
  { code: 'WB_TRUCK', form: '2-Б', num: '06', days: 15, pax: false, intl: false },
  { code: 'WB_TRUCK_INTL', form: '5Б-БМ', num: '07', days: 30, pax: false, intl: true },
  { code: 'WB_PAX_INTL', form: '4М-БМ', num: '08', days: 30, pax: true, intl: true },
  { code: 'WB_SPECIAL', form: 'спецтехника', num: '09', days: 7, pax: false, intl: false },
  { code: 'WB_DANGEROUS', form: 'опасные грузы', num: '10', days: 1, pax: false, intl: false },
];

export default function TypesSettingsPage() {
  const { t, tType } = useT();
  const { roles } = useAuth();
  const canEdit = roles.includes('SYSTEM_ADMIN');
  const [names, setNames] = useState<Record<string, { ru: string; tj: string }>>({});
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  function load() {
    md.classifiers('WAYBILL_TYPE', true)
      .then(list => setNames(Object.fromEntries(list.map(c => [c.code, { ru: c.nameRu, tj: c.nameTj ?? '' }]))))
      .catch(() => { /* нет доступа/связи — поля будут пустыми, плейсхолдер покажет дефолт */ });
  }
  useEffect(() => { load(); }, []);

  const set = (code: string, field: 'ru' | 'tj', v: string) =>
    setNames(s => ({ ...s, [code]: { ...(s[code] ?? { ru: '', tj: '' }), [field]: v } }));

  async function save(code: string, sortOrder: number) {
    setBusy(code); setErr(''); setMsg('');
    try {
      const n = names[code] ?? { ru: '', tj: '' };
      if (!n.ru.trim()) { setErr(t('settypes.needname')); setBusy(''); return; }
      await md.saveClassifier({ category: 'WAYBILL_TYPE', code, nameRu: n.ru.trim(), nameTj: n.tj.trim() || null, sortOrder, active: true });
      setMsg(t('settypes.saved'));
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(''); }
  }

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.wbtypes')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('settypes.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 14 }}>{t('settypes.editnote')}</div>
      {msg && <div className="success" style={{ marginBottom: 12 }}>{msg}</div>}
      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      <div className="card" style={{ padding: 0, overflowX: 'auto' }}>
        <table>
          <thead>
            <tr>
              <th>{t('col.code')}</th>
              <th>{t('settypes.nameru')}</th>
              <th>{t('settypes.nametj')}</th>
              <th>{t('settypes.form')}</th>
              <th>{t('settypes.natcode')}</th>
              <th>{t('settypes.validity')}</th>
              <th>{t('settypes.category')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {TYPES.map((x, i) => (
              <tr key={x.code}>
                <td><span className="badge gray" style={{ fontFamily: 'var(--mono)' }}>{x.code}</span></td>
                <td><input value={names[x.code]?.ru ?? ''} disabled={!canEdit} placeholder={tType(x.code)}
                  onChange={e => set(x.code, 'ru', e.target.value)} style={{ minWidth: 180 }} /></td>
                <td><input value={names[x.code]?.tj ?? ''} disabled={!canEdit}
                  onChange={e => set(x.code, 'tj', e.target.value)} style={{ minWidth: 160 }} /></td>
                <td><span className="badge blue" style={{ fontFamily: 'var(--mono)' }}>{x.form}</span></td>
                <td><span className="number">{x.num}</span></td>
                <td style={{ whiteSpace: 'nowrap' }}>{x.days} {t('unit.days')}</td>
                <td style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <span className={`badge ${x.pax ? 'teal' : 'gray'}`}>{x.pax ? t('settypes.pax') : t('settypes.freight')}</span>
                  {x.intl && <span className="badge amber">{t('settypes.intl')}</span>}
                </td>
                <td>{canEdit && (
                  <button className="btn" style={{ padding: '5px 12px', fontSize: 12.5 }} disabled={busy === x.code} onClick={() => save(x.code, i + 1)}>
                    {busy === x.code ? '…' : t('fleet.save')}
                  </button>
                )}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="hint" style={{ marginTop: 10 }}>{t('settypes.applynote')}</div>
    </>
  );
}
