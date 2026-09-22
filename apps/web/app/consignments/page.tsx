'use client';

import { useCallback, useEffect, useState } from 'react';
import { wb, type Waybill, type ConsignmentPage } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type CargoOp = { operation: string; executorName: string; method: string; arrival: string; departure: string; downtimeMinutes: string; additionalOps: string; signature: string };
const str = (v: unknown) => (v == null ? '' : String(v));

/**
 * Кабинет накладных внешних пользователей (MIGRATION.md 1.1/3.11 — legacy client_sender / client_forwarder /
 * customs_officer): грузоотправитель и экспедитор видят и правят накладные (борхат 2-Б, СМР 5Б-БМ) своих клиентов,
 * таможенник — все СМР и подтверждает их. Область — клиенты из токена / роль, не организация.
 */
export default function ConsignmentsPage() {
  const { roles } = useAuth();
  const { t, tType } = useT();
  const isCustoms = roles.includes('CUSTOMS_OFFICER');
  const canEdit = roles.includes('CLIENT_SENDER') || roles.includes('CLIENT_FORWARDER');
  const [filters, setFilters] = useState({ from: '', to: '', q: '', unconfirmed: false });
  const [page, setPage] = useState(1);
  const [data, setData] = useState<ConsignmentPage | null>(null);
  const [current, setCurrent] = useState<Waybill | null>(null);
  const [form, setForm] = useState<Record<string, string>>({});
  const [ops, setOps] = useState<CargoOp[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    wb.consignments.list({ from: filters.from, to: filters.to, q: filters.q.trim(), unconfirmed: filters.unconfirmed, page: page - 1, size: 20 })
      .then(d => { setData(d); setError(''); }).catch(e => setError((e as Error).message));
  }, [filters, page]);
  useEffect(() => { const h = setTimeout(load, 250); return () => clearTimeout(h); }, [load]);

  async function open(id: string) {
    setError(''); setOk('');
    try {
      const w = await wb.consignments.get(id);
      setCurrent(w);
      const td = w.typeData ?? {};
      setForm({ cargoName: str(td.cargoName), cargoVolume: str(td.cargoVolume), cargoStatCode: str(td.cargoStatCode), submittedDocuments: str(td.submittedDocuments), senderAddress: str(td.senderAddress), receiverAddress: str(td.receiverAddress), tripsCount: td.trips == null ? '' : String(Math.trunc(Number(td.trips))) });
      const o = Array.isArray(td.cargoOperations) ? (td.cargoOperations as Record<string, unknown>[]) : [];
      setOps(o.map(x => ({ operation: str(x.operation), executorName: str(x.executorName), method: str(x.method), arrival: str(x.arrival), departure: str(x.departure), downtimeMinutes: str(x.downtimeMinutes), additionalOps: str(x.additionalOps), signature: str(x.signature) })));
    } catch (e) { setError((e as Error).message); }
  }

  async function save() {
    if (!current) return;
    setBusy(true); setError(''); setOk('');
    try {
      const w = await wb.consignments.update(current.id, {
        cargoName: form.cargoName || null, cargoVolume: form.cargoVolume ? Number(form.cargoVolume) : null,
        cargoStatCode: form.cargoStatCode || null, submittedDocuments: form.submittedDocuments || null,
        senderAddress: form.senderAddress || null, receiverAddress: form.receiverAddress || null,
        tripsCount: form.tripsCount ? Math.trunc(Number(form.tripsCount)) : null, cargoOperations: ops,
      });
      setCurrent(w); setOk(t('cs.saved')); load();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  async function confirm() {
    if (!current) return;
    setBusy(true); setError(''); setOk('');
    try { const w = await wb.consignments.customsConfirm(current.id); setCurrent(w); setOk(t('cs.confirmed')); load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  async function print() {
    if (!current) return;
    try { const blob = await wb.consignments.printPdf(current.id); const url = URL.createObjectURL(blob); window.open(url, '_blank'); setTimeout(() => URL.revokeObjectURL(url), 60_000); }
    catch (e) { setError((e as Error).message); }
  }

  const fmt = (d: unknown) => (d ? new Date(String(d)).toLocaleString('ru-RU') : '—');
  const rows = data?.content ?? [];
  const pages = Math.max(1, data?.totalPages ?? 1);
  const td = (current?.typeData ?? {}) as Record<string, unknown>;
  const confirmedAt = str(td.customsConfirmedAt);
  const field = (key: string, label: string, type = 'text') => (
    <div><label style={{ display: 'block', fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>{label}</label>
      <input type={type} value={form[key] ?? ''} disabled={!canEdit} onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))} style={{ width: '100%' }} /></div>
  );

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.consignments')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{isCustoms ? t('cs.lead.customs') : t('cs.lead.client')}</div>
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      {ok && <div className="success">{ok}</div>}

      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, alignItems: 'end' }}>
          <div><label>{t('flt.datefrom')}</label><input type="date" value={filters.from} onChange={e => { setFilters(f => ({ ...f, from: e.target.value })); setPage(1); }} /></div>
          <div><label>{t('flt.dateto')}</label><input type="date" value={filters.to} onChange={e => { setFilters(f => ({ ...f, to: e.target.value })); setPage(1); }} /></div>
          <div><label>{t('wb.f.search')}</label><input value={filters.q} onChange={e => { setFilters(f => ({ ...f, q: e.target.value })); setPage(1); }} placeholder={t('cs.search.ph')} /></div>
          {isCustoms && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingBottom: 8 }}>
              <input type="checkbox" id="cs-unc" checked={filters.unconfirmed} onChange={e => { setFilters(f => ({ ...f, unconfirmed: e.target.checked })); setPage(1); }} />
              <label htmlFor="cs-unc" style={{ margin: 0 }}>{t('cs.f.unconfirmed')}</label>
            </div>
          )}
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: current ? '1fr 1fr' : '1fr', gap: 14, alignItems: 'start' }}>
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <table>
            <thead><tr><th>{t('col.wbnum')}</th><th>{t('col.type')}</th><th>{t('col.company')}</th><th>{t('col.transport')}</th><th>{t('cn.f.sender')}</th><th>{t('cn.f.receiver')}</th><th>{t('wb.cargo')}</th>{isCustoms && <th>{t('cs.col.customs')}</th>}</tr></thead>
            <tbody>
              {rows.map(w => {
                const x = (w.typeData ?? {}) as Record<string, unknown>;
                return (
                  <tr key={w.id} style={{ cursor: 'pointer', background: current?.id === w.id ? 'var(--line-soft)' : undefined }} onClick={() => open(w.id)}>
                    <td><span className="number">{w.number ?? '—'}</span></td>
                    <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                    <td>{str(w.organizationSnapshot?.name ?? w.organizationRma)}</td>
                    <td>{w.vehicleRegNumber}</td>
                    <td>{str(x.senderName) || '—'}</td><td>{str(x.receiverName) || '—'}</td><td>{str(x.cargoName) || '—'}</td>
                    {isCustoms && <td>{str(x.customsConfirmedAt) ? <span className="badge green">{t('cs.status.confirmed')}</span> : <span className="badge amber">{t('cs.status.pending')}</span>}</td>}
                  </tr>
                );
              })}
              {rows.length === 0 && <tr><td colSpan={isCustoms ? 8 : 7} style={{ textAlign: 'center', color: 'var(--muted)', padding: 24 }}>{t('cs.empty')}</td></tr>}
            </tbody>
          </table>
          <div style={{ display: 'flex', alignItems: 'center', padding: '12px 16px', borderTop: '1px solid var(--line)', fontSize: 13, color: 'var(--muted)' }}>
            <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{data?.totalElements ?? 0}</b></span>
            <span style={{ flex: 1 }} />
            <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
            <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
            <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
          </div>
        </div>

        {current && (
          <div className="card">
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 8 }}>
              <h2 style={{ margin: 0 }}>{current.waybillType === 'WB_TRUCK_INTL' ? 'CMR' : t('cn.h')} · {current.number ?? '—'}</h2>
              <span className="badge gray">{tType(current.waybillType)}</span>
              <span style={{ flex: 1 }} />
              <button className="btn secondary" onClick={() => setCurrent(null)}>×</button>
            </div>
            <dl className="kv">
              <dt>{t('col.company')}</dt><dd>{str(current.organizationSnapshot?.name ?? current.organizationRma)}</dd>
              <dt>{t('col.transport')}</dt><dd>{current.vehicleRegNumber}</dd>
              <dt>{t('col.driver')}</dt><dd>{str(current.driverSnapshot?.fullName ?? current.driverRma)}</dd>
              <dt>{t('cn.f.sender')}</dt><dd>{str(td.senderName) || '—'}</dd>
              <dt>{t('cn.f.receiver')}</dt><dd>{str(td.receiverName) || '—'}</dd>
              {str(td.forwarderName) && <><dt>{t('cn.f.forwarder')}</dt><dd>{str(td.forwarderName)}</dd></>}
              {current.waybillType === 'WB_TRUCK_INTL' && <><dt>{t('cs.col.customs')}</dt><dd>{confirmedAt ? `${t('cs.status.confirmed')} · ${str(td.customsOfficerName)} · ${fmt(confirmedAt)}` : t('cs.status.pending')}</dd></>}
            </dl>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 10, marginTop: 10 }}>
              {field('cargoName', t('cn.f.cargo'))}
              {field('cargoVolume', t('cn.f.volume'), 'number')}
              {field('cargoStatCode', t('cn.f.statcode'))}
              {field('submittedDocuments', t('cn.f.docs'))}
              {field('senderAddress', t('cn.f.senderaddr'))}
              {field('receiverAddress', t('cn.f.receiveraddr'))}
              {current.waybillType === 'WB_TRUCK_INTL' && field('tripsCount', t('cn.f.trips'), 'number')}
            </div>
            {ops.length > 0 && (
              <table style={{ marginTop: 12 }}>
                <thead><tr><th>{t('cn.col.op')}</th><th>{t('cn.col.executor')}</th><th>{t('cn.col.arrival')}</th><th>{t('cn.col.departure')}</th><th>{t('cn.col.downtime')}</th></tr></thead>
                <tbody>{ops.map((o, i) => (
                  <tr key={i}><td>{o.operation}</td>
                    <td><input value={o.executorName} disabled={!canEdit} onChange={e => setOps(p => p.map((x, j) => j === i ? { ...x, executorName: e.target.value } : x))} style={{ width: 140 }} /></td>
                    <td><input value={o.arrival} disabled={!canEdit} onChange={e => setOps(p => p.map((x, j) => j === i ? { ...x, arrival: e.target.value } : x))} style={{ width: 80 }} /></td>
                    <td><input value={o.departure} disabled={!canEdit} onChange={e => setOps(p => p.map((x, j) => j === i ? { ...x, departure: e.target.value } : x))} style={{ width: 80 }} /></td>
                    <td><input type="number" value={o.downtimeMinutes} disabled={!canEdit} onChange={e => setOps(p => p.map((x, j) => j === i ? { ...x, downtimeMinutes: e.target.value } : x))} style={{ width: 70 }} /></td>
                  </tr>))}</tbody>
              </table>
            )}
            <div style={{ display: 'flex', gap: 8, marginTop: 14, flexWrap: 'wrap' }}>
              {canEdit && <button className="btn" onClick={save} disabled={busy}>{busy ? t('cn.btn.saving') : t('btn.save')}</button>}
              <button className="btn secondary" onClick={print}><Icon d={P.doc} cls="" style={{ width: 15, height: 15 }} /> PDF</button>
              {isCustoms && current.waybillType === 'WB_TRUCK_INTL' && !confirmedAt && (
                <button className="btn" onClick={confirm} disabled={busy}>{t('cs.btn.confirm')}</button>
              )}
            </div>
          </div>
        )}
      </div>
    </>
  );
}
