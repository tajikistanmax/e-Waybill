'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { md, type FeeTariff, type BalanceView } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Все типы ПЛ (WaybillType) — для строк тарифа (решение владельца 31.10.2026: платная выдача ПЛ). */
const ALL_TYPES = [
  'WB_CAR', 'WB_TAXI', 'WB_MINIBUS', 'WB_BUS', 'WB_TROLLEYBUS', 'WB_TRUCK',
  'WB_TRUCK_INTL', 'WB_PAX_INTL', 'WB_SPECIAL', 'WB_DANGEROUS', 'WB_MOTORCYCLE',
];

export default function BillingSettingsPage() {
  const { t, tType } = useT();
  const { roles } = useAuth();
  const isSysAdmin = roles.includes('SYSTEM_ADMIN');

  const [tariffs, setTariffs] = useState<FeeTariff[]>([]);
  const [orgs, setOrgs] = useState<Record<string, unknown>[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);

  const [orgQuery, setOrgQuery] = useState('');
  const [overrideOrgId, setOverrideOrgId] = useState('');
  const [overrideType, setOverrideType] = useState(ALL_TYPES[0]);
  const [overrideAmount, setOverrideAmount] = useState('');

  const [balanceQuery, setBalanceQuery] = useState('');
  const [balanceOrgRma, setBalanceOrgRma] = useState('');
  const [balanceView, setBalanceView] = useState<BalanceView | null>(null);
  const [topUpAmount, setTopUpAmount] = useState('');
  const [topUpRef, setTopUpRef] = useState('');
  const [topUpNote, setTopUpNote] = useState('');

  const reload = useCallback(async () => {
    try { setTariffs(await md.feeTariffs()); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }, []);

  useEffect(() => { if (isSysAdmin) reload(); }, [isSysAdmin, reload]);
  useEffect(() => { if (isSysAdmin) md.organizations().then(setOrgs).catch(() => setOrgs([])); }, [isSysAdmin]);

  const flash = (msg: string) => { setOk(msg); setTimeout(() => setOk(''), 2000); };

  const orgLabel = useCallback((id: string | null) => {
    if (!id) return null;
    const o = orgs.find(o => String(o.id) === id);
    return o ? `${String(o.name)} · ${String(o.rma)}` : id;
  }, [orgs]);

  const matchingOrgs = useMemo(() => {
    const q = orgQuery.trim().toLowerCase();
    if (!q) return [];
    return orgs.map(o => ({ id: String(o.id ?? ''), rma: String(o.rma ?? ''), name: String(o.name ?? '') }))
      .filter(o => o.name.toLowerCase().includes(q) || o.rma.includes(q)).slice(0, 20);
  }, [orgs, orgQuery]);

  const matchingBalanceOrgs = useMemo(() => {
    const q = balanceQuery.trim().toLowerCase();
    if (!q) return [];
    return orgs.map(o => ({ rma: String(o.rma ?? ''), name: String(o.name ?? '') }))
      .filter(o => o.name.toLowerCase().includes(q) || o.rma.includes(q)).slice(0, 20);
  }, [orgs, balanceQuery]);

  const national = (type: string) => tariffs.find(x => x.waybillType === type && !x.organizationId);
  const overrides = tariffs.filter(x => x.organizationId).sort((a, b) => a.waybillType.localeCompare(b.waybillType));

  async function saveNational(type: string, amount: string) {
    const n = Number(amount);
    if (!Number.isFinite(n) || n < 0) return;
    setBusy(true); setError('');
    try { await md.setNationalFee(type, n); await reload(); flash(t('bill.saved')); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function addOverride() {
    const n = Number(overrideAmount);
    if (!overrideOrgId || !Number.isFinite(n) || n < 0) return;
    setBusy(true); setError('');
    try {
      await md.setOrganizationFee(overrideType, overrideOrgId, n);
      await reload();
      setOverrideOrgId(''); setOverrideAmount(''); setOrgQuery('');
      flash(t('bill.saved'));
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function removeOverride(id: string) {
    setBusy(true); setError('');
    try { await md.deleteFeeTariff(id); await reload(); flash(t('bill.saved')); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function loadBalance(rma: string) {
    setBusy(true); setError('');
    try { setBalanceOrgRma(rma); setBalanceView(await md.balance(rma)); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  async function topUp() {
    const n = Number(topUpAmount);
    if (!balanceOrgRma || !Number.isFinite(n) || n <= 0 || !topUpRef.trim()) return;
    setBusy(true); setError('');
    try {
      await md.topUpBalance(balanceOrgRma, n, topUpRef.trim(), topUpNote.trim() || undefined);
      setTopUpAmount(''); setTopUpRef(''); setTopUpNote('');
      await loadBalance(balanceOrgRma);
      flash(t('bill.saved'));
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  if (!isSysAdmin) {
    return <div className="hint">{t('bill.adminonly')}</div>;
  }

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.billing')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('bill.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('bill.note')}</div>
      {error && <div className="error" style={{ marginBottom: 14 }}>{error}</div>}
      {ok && <div className="badge green" style={{ marginBottom: 14 }}>{ok}</div>}

      {/* Национальные ставки по типу ПЛ */}
      <div className="card" style={{ marginBottom: 18 }}>
        <h3 style={{ margin: '4px 0 12px' }}>{t('bill.national')}</h3>
        <table>
          <thead><tr><th>{t('col.type')}</th><th style={{ width: 160 }}>{t('bill.amount')}</th></tr></thead>
          <tbody>
            {ALL_TYPES.map(type => {
              const row = national(type);
              return (
                <tr key={type}>
                  <td style={{ fontWeight: 600 }}>{tType(type)}</td>
                  <td>
                    <AmountEditor key={`${type}-${row?.amountTjs ?? ''}`} current={row ? String(row.amountTjs) : ''}
                      disabled={busy} onSave={v => saveNational(type, v)} t={t} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="hint" style={{ marginTop: 10 }}>{t('bill.zerohint')}</div>
      </div>

      {/* Переопределения по организации */}
      <div className="card" style={{ marginBottom: 18 }}>
        <h3 style={{ margin: '4px 0 12px' }}>{t('bill.byorg')}</h3>
        <table>
          <thead><tr><th>{t('pol.org')}</th><th>{t('col.type')}</th><th style={{ width: 140 }}>{t('bill.amount')}</th><th style={{ width: 80 }} /></tr></thead>
          <tbody>
            {overrides.length === 0 && <tr><td colSpan={4} style={{ color: 'var(--muted)' }}>{t('pol.empty')}</td></tr>}
            {overrides.map(o => (
              <tr key={o.id}>
                <td>{orgLabel(o.organizationId) ?? o.organizationId}</td>
                <td>{tType(o.waybillType)}</td>
                <td className="number">{o.amountTjs.toFixed(2)}</td>
                <td>
                  <button title={t('pol.remove')} disabled={busy} onClick={() => removeOverride(o.id)}
                    style={{ background: 'none', border: '1px solid var(--line)', borderRadius: 6, padding: '4px 8px', cursor: 'pointer', color: 'var(--red)' }}>
                    <Icon d={P.trash} cls="" style={{ width: 15, height: 15 }} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5, position: 'relative' }}>
            {t('pol.org')}
            <input
              value={overrideOrgId ? (orgLabel(overrideOrgId) ?? overrideOrgId) : orgQuery}
              onChange={e => { setOverrideOrgId(''); setOrgQuery(e.target.value); }}
              placeholder={t('pol.org.search')} style={{ width: 260 }} />
            {!overrideOrgId && matchingOrgs.length > 0 && (
              <div className="card" style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 20, maxHeight: 220, overflowY: 'auto', padding: 4, marginTop: 2 }}>
                {matchingOrgs.map(o => (
                  <div key={o.id} role="button" onClick={() => { setOverrideOrgId(o.id); setOrgQuery(''); }}
                    style={{ padding: '6px 8px', cursor: 'pointer', fontSize: 13, borderRadius: 4 }}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--blue-050)')}
                    onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                    {o.name} · <span className="number">{o.rma}</span>
                  </div>
                ))}
              </div>
            )}
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('col.type')}
            <select value={overrideType} onChange={e => setOverrideType(e.target.value)}>
              {ALL_TYPES.map(ty => <option key={ty} value={ty}>{tType(ty)}</option>)}
            </select>
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
            {t('bill.amount')}
            <input type="number" min={0} step="0.01" value={overrideAmount}
              onChange={e => setOverrideAmount(e.target.value)} style={{ width: 100 }} />
          </label>
          <button className="btn" disabled={busy || !overrideOrgId || !overrideAmount.trim()} onClick={addOverride}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('pol.add')}
          </button>
        </div>
      </div>

      {/* Баланс организации — просмотр и пополнение */}
      <div className="card">
        <h3 style={{ margin: '4px 0 12px' }}>{t('bill.balance')}</h3>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', position: 'relative', marginBottom: 14 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5, position: 'relative' }}>
            {t('pol.org')}
            <input value={balanceQuery} onChange={e => setBalanceQuery(e.target.value)}
              placeholder={t('pol.org.search')} style={{ width: 280 }} />
            {balanceQuery && matchingBalanceOrgs.length > 0 && (
              <div className="card" style={{ position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 20, maxHeight: 220, overflowY: 'auto', padding: 4, marginTop: 2 }}>
                {matchingBalanceOrgs.map(o => (
                  <div key={o.rma} role="button" onClick={() => { setBalanceQuery(''); loadBalance(o.rma); }}
                    style={{ padding: '6px 8px', cursor: 'pointer', fontSize: 13, borderRadius: 4 }}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--blue-050)')}
                    onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                    {o.name} · <span className="number">{o.rma}</span>
                  </div>
                ))}
              </div>
            )}
          </label>
        </div>

        {balanceView && (
          <>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 14 }}>
              <span style={{ fontSize: 13, color: 'var(--muted)' }}>{t('bill.currentbalance')} ({balanceOrgRma}):</span>
              <span className={`number`} style={{ fontSize: 20, fontWeight: 700, color: balanceView.balanceTjs < 0 ? 'var(--red)' : 'var(--green)' }}>
                {balanceView.balanceTjs.toFixed(2)} TJS
              </span>
            </div>

            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 14, paddingBottom: 14, borderBottom: '1px solid var(--line)' }}>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('bill.amount')}
                <input type="number" min={0.01} step="0.01" value={topUpAmount} onChange={e => setTopUpAmount(e.target.value)} style={{ width: 120 }} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('bill.reference')}
                <input value={topUpRef} onChange={e => setTopUpRef(e.target.value)} placeholder={t('bill.referencehint')} style={{ width: 200 }} />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12.5 }}>
                {t('bill.notefield')}
                <input value={topUpNote} onChange={e => setTopUpNote(e.target.value)} style={{ width: 220 }} />
              </label>
              <button className="btn" disabled={busy || !topUpAmount.trim() || !topUpRef.trim()} onClick={topUp}>
                <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('bill.topup')}
              </button>
            </div>

            <table>
              <thead><tr><th>{t('bill.date')}</th><th>{t('bill.direction')}</th><th>{t('col.type')}</th><th>{t('bill.amount')}</th><th>{t('bill.reference')}/{t('bill.notefield')}</th></tr></thead>
              <tbody>
                {balanceView.ledger.content.length === 0 && <tr><td colSpan={5} style={{ color: 'var(--muted)' }}>{t('pol.empty')}</td></tr>}
                {balanceView.ledger.content.map(e => (
                  <tr key={e.id}>
                    <td>{e.createdAt ? new Date(e.createdAt).toLocaleString() : '—'}</td>
                    <td><span className={`badge ${e.direction === 'CREDIT' ? 'green' : 'amber'}`}>
                      {e.direction === 'CREDIT' ? t('bill.credit') : t('bill.debit')}
                    </span></td>
                    <td>{e.waybillType ? tType(e.waybillType) : '—'}</td>
                    <td className="number">{e.direction === 'CREDIT' ? '+' : '-'}{e.amountTjs.toFixed(2)}</td>
                    <td style={{ fontSize: 12.5, color: 'var(--muted)' }}>{e.reference ?? e.note ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </div>
    </>
  );
}

/** Денежная сумма с сохранением по кнопке (пусто/0 — бесплатно для этого типа). */
function AmountEditor({ current, disabled, onSave, t }: {
  current: string; disabled?: boolean; onSave: (v: string) => void; t: (k: string) => string;
}) {
  const [v, setV] = useState(current);
  const dirty = v.trim() !== current;
  return (
    <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
      <input type="number" min={0} step="0.01" value={v} disabled={disabled}
        onChange={e => setV(e.target.value)} placeholder="0.00" style={{ width: 90 }} />
      {dirty && !disabled && (
        <button type="button" className="badge blue" style={{ cursor: 'pointer', border: 'none' }}
          onClick={() => onSave(v.trim() || '0')}>{t('pol.set')}</button>
      )}
    </span>
  );
}
