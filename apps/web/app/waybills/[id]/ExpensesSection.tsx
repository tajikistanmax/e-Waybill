'use client';

import { useCallback, useEffect, useState } from 'react';
import { wb, type Expense } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

const TYPES = ['PER_DIEM', 'TOLL', 'PARKING', 'LODGING', 'REPAIR', 'OTHER'] as const;

/** Расходы рейса (§12): список с итогом, добавление, подтверждение бухгалтером, удаление. */
export function ExpensesSection({ waybillId, terminal }: { waybillId: string; terminal: boolean }) {
  const { t } = useT();
  const { roles } = useAuth();
  const canAdd = !terminal && (roles.includes('DISPATCHER') || roles.includes('DRIVER') || roles.includes('SYSTEM_ADMIN'));
  const canConfirm = roles.includes('ACCOUNTANT') || roles.includes('SYSTEM_ADMIN');
  const canDelete = roles.includes('DISPATCHER') || roles.includes('SYSTEM_ADMIN');

  const [rows, setRows] = useState<Expense[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const blank = { expenseType: 'PER_DIEM', amount: '', currency: 'TJS', vat: '', description: '', receiptNumber: '', spentAt: '' };
  const [form, setForm] = useState<Record<string, string>>(blank);

  const load = useCallback(() => {
    setLoading(true);
    wb.expenses(waybillId).then(setRows).catch(e => setErr((e as Error).message)).finally(() => setLoading(false));
  }, [waybillId]);
  useEffect(() => { load(); }, [load]);

  const total = rows.reduce((s, e) => s + Number(e.amount || 0), 0);
  const money = (v: number) => v.toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  async function add() {
    if (!form.amount.trim()) return;
    setBusy(true); setErr('');
    try {
      await wb.addExpense(waybillId, {
        expenseType: form.expenseType,
        amount: Number(form.amount),
        currency: form.currency.trim() || 'TJS',
        vat: form.vat.trim() ? Number(form.vat) : undefined,
        description: form.description.trim() || undefined,
        receiptNumber: form.receiptNumber.trim() || undefined,
        spentAt: form.spentAt || undefined,
      });
      setForm(blank); setAdding(false); load();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  async function confirm(eid: string) {
    setErr('');
    try { await wb.confirmExpense(eid); load(); } catch (e) { setErr((e as Error).message); }
  }
  async function remove(eid: string) {
    if (!window.confirm(t('wexp.delete.confirm'))) return;
    setErr('');
    try { await wb.deleteExpense(eid); load(); } catch (e) { setErr((e as Error).message); }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 14 }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.total')}</div>
          <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--ink)' }}>{money(total)} <span style={{ fontSize: 13, color: 'var(--muted)' }}>TJS</span></div>
        </div>
        {canAdd && (
          <button className="btn" style={{ marginLeft: 'auto' }} onClick={() => setAdding(a => !a)}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('wexp.add')}
          </button>
        )}
      </div>

      {err && <div className="error" style={{ marginBottom: 12 }}>{err}</div>}

      {canAdd && adding && (
        <div className="card" style={{ padding: 16, marginBottom: 14 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 12 }}>
            <div>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.f.type')} *</label>
              <select value={form.expenseType} onChange={e => setForm(s => ({ ...s, expenseType: e.target.value }))} style={{ width: '100%', marginTop: 4 }}>
                {TYPES.map(tp => <option key={tp} value={tp}>{t(`wexp.type.${tp}`)}</option>)}
              </select>
            </div>
            <div>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.f.amount')} *</label>
              <input type="number" min={0} step="0.01" value={form.amount} onChange={e => setForm(s => ({ ...s, amount: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.f.currency')}</label>
              <input value={form.currency} onChange={e => setForm(s => ({ ...s, currency: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.f.vat')}</label>
              <input type="number" min={0} step="0.01" value={form.vat} onChange={e => setForm(s => ({ ...s, vat: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.f.receipt')}</label>
              <input value={form.receiptNumber} onChange={e => setForm(s => ({ ...s, receiptNumber: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.f.date')}</label>
              <input type="date" value={form.spentAt} onChange={e => setForm(s => ({ ...s, spentAt: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
            </div>
            <div style={{ gridColumn: '1 / -1' }}>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('wexp.f.desc')}</label>
              <input value={form.description} onChange={e => setForm(s => ({ ...s, description: e.target.value }))} style={{ width: '100%', marginTop: 4 }} />
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
            <button className="btn primary" disabled={busy || !form.amount.trim()} onClick={add}>{busy ? '…' : t('wexp.save')}</button>
            <button className="btn secondary" onClick={() => { setAdding(false); setForm(blank); }}>{t('btn.cancel')}</button>
          </div>
        </div>
      )}

      <table>
        <thead>
          <tr><th>{t('wexp.f.type')}</th><th>{t('wexp.f.amount')}</th><th>{t('wexp.f.receipt')}</th><th>{t('wexp.f.date')}</th><th>{t('wexp.status')}</th><th style={{ textAlign: 'right' }} /></tr>
        </thead>
        <tbody>
          {loading && <tr><td colSpan={6} style={{ color: 'var(--muted)' }}>{t('mon.loading')}</td></tr>}
          {!loading && rows.length === 0 && <tr><td colSpan={6} style={{ color: 'var(--muted)' }}>{t('wexp.empty')}</td></tr>}
          {!loading && rows.map(e => (
            <tr key={e.id}>
              <td>{t(`wexp.type.${e.expenseType}`)}{e.description ? <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>{e.description}</div> : null}</td>
              <td style={{ fontWeight: 600 }}>{money(Number(e.amount))} {e.currency}{e.vat ? <div style={{ fontSize: 11, color: 'var(--muted)' }}>{t('wexp.f.vat')}: {money(Number(e.vat))}</div> : null}</td>
              <td>{e.receiptNumber ?? '—'}</td>
              <td>{e.spentAt ?? '—'}</td>
              <td>{e.confirmed
                ? <span className="badge green">{t('wexp.confirmed')}</span>
                : <span className="badge gray">{t('wexp.pending')}</span>}</td>
              <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                {!e.confirmed && canConfirm && (
                  <button className="btn secondary" style={{ padding: '4px 9px', marginRight: 6 }} onClick={() => confirm(e.id)}>{t('wexp.confirm')}</button>
                )}
                {!e.confirmed && canDelete && (
                  <button className="btn secondary" style={{ padding: '4px 9px', color: 'var(--red)' }} onClick={() => remove(e.id)}>
                    <Icon d={P.trash ?? P.alert} cls="" style={{ width: 14, height: 14 }} />
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
