'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { md, wb, type ConsignmentNoteRegistryPage, type ConsignmentNoteRegistryRow } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';

/**
 * Реестр борхатов 2-Б (legacy «Борхатҳои замимаи 1 / 2», CWControllerTrait): фильтры «дата борхата»,
 * «корхона», «фиристанда», «интиқолдиҳанда», вид и поиск по номеру; итоги по отбору (P, Z, тонны).
 * Область видимости задаёт сервер: Минтранс — все, перевозчик — свои организации, грузоотправитель
 * и экспедитор — свои борхаты. Печать — отдельным бланком на каждый борхат.
 */
const today = () => new Date().toISOString().slice(0, 10);
const monthStart = () => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1, 12).toISOString().slice(0, 10); };
const fmt = (v: number | null | undefined, d = 2) => (v == null ? '—' : (Math.round(Number(v) * 10 ** d) / 10 ** d).toLocaleString('ru-RU'));

export default function ConsignmentNotesRegistry() {
  const { t } = useT();
  const { roles } = useAuth();
  const platform = roles.includes('SYSTEM_ADMIN') || roles.includes('MINTRANS_ANALYST');
  const external = !platform && (roles.includes('CLIENT_SENDER') || roles.includes('CLIENT_FORWARDER'));
  const [f, setF] = useState({ from: monthStart(), to: today(), organizationRma: '', sender: '', forwarder: '', q: '', kind: '' });
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ConsignmentNoteRegistryPage | null>(null);
  const [orgs, setOrgs] = useState<{ rma: string; name: string }[]>([]);
  const [error, setError] = useState('');
  const [printing, setPrinting] = useState('');

  useEffect(() => {
    if (!platform) return;
    md.organizations()
      .then(list => setOrgs(list.map(o => ({ rma: String(o.rma), name: String(o.name ?? o.rma) }))
        .sort((a, b) => a.name.localeCompare(b.name))))
      .catch(() => setOrgs([]));
  }, [platform]);

  const load = useCallback(() => {
    const params: Record<string, string> = { page: String(page), size: '50' };
    Object.entries(f).forEach(([k, v]) => { if (v.trim()) params[k] = v.trim(); });
    wb.noteRegistry(params).then(d => { setData(d); setError(''); }).catch(e => setError((e as Error).message));
  }, [f, page]);
  useEffect(() => { const h = setTimeout(load, 250); return () => clearTimeout(h); }, [load]);

  const set = (k: keyof typeof f, v: string) => { setF(x => ({ ...x, [k]: v })); setPage(0); };

  async function print(n: ConsignmentNoteRegistryRow) {
    setPrinting(n.id);
    try {
      const url = URL.createObjectURL(await wb.printRegistryNotePdf(n.id));
      window.open(url, '_blank');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPrinting('');
    }
  }

  const rows = data?.content ?? [];
  const tt = data?.totals;
  const pages = Math.max(1, data?.totalPages ?? 1);

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('cnr.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t(external ? 'cnr.lead.client' : 'cnr.lead')}</div>
        </div>
      </div>
      {error && <div className="error">{error}</div>}

      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12, alignItems: 'end' }}>
          <div><label>{t('flt.datefrom')}</label><input type="date" aria-label={t('flt.datefrom')} value={f.from} onChange={e => set('from', e.target.value)} /></div>
          <div><label>{t('flt.dateto')}</label><input type="date" aria-label={t('flt.dateto')} value={f.to} onChange={e => set('to', e.target.value)} /></div>
          {platform && (
            <div><label>{t('col.company')}</label>
              <select aria-label={t('col.company')} value={f.organizationRma} onChange={e => set('organizationRma', e.target.value)}>
                <option value="">{t('cnr.allorgs')}</option>
                {orgs.map(o => <option key={o.rma} value={o.rma}>{o.name}</option>)}
              </select></div>
          )}
          <div><label>{t('cn.f.sender')}</label><input aria-label={t('cn.f.sender')} value={f.sender} onChange={e => set('sender', e.target.value)} /></div>
          <div><label>{t('cn.f.forwarder')}</label><input aria-label={t('cn.f.forwarder')} value={f.forwarder} onChange={e => set('forwarder', e.target.value)} /></div>
          <div><label>{t('cnn.f.kind')}</label>
            <select aria-label={t('cnn.f.kind')} value={f.kind} onChange={e => set('kind', e.target.value)}>
              <option value="">{t('cnr.allkinds')}</option>
              <option value="1">{t('cnn.kind1')}</option><option value="2">{t('cnn.kind2')}</option>
            </select></div>
          <div><label>{t('wb.f.search')}</label><input aria-label={t('wb.f.search')} value={f.q} placeholder={t('cnr.search.ph')} onChange={e => set('q', e.target.value)} /></div>
        </div>
      </div>

      {tt && (
        <div className="card" data-testid="cnr-totals" style={{ display: 'flex', gap: 28, flexWrap: 'wrap', fontSize: 14 }}>
          <span>{t('cnn.total')}: <b>{tt.count.toLocaleString('ru-RU')}</b></span>
          <span>{t('cnr.tons')}: <b>{fmt(tt.weight, 3)}</b></span>
          <span>P, т·км: <b>{fmt(tt.transportWork)}</b></span>
          <span>Z: <b>{fmt(tt.trips, 0)}</b></span>
        </div>
      )}

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ overflowX: 'auto' }}>
          <table className="dense">
            <thead><tr>
              <th>№</th><th>{t('col.date')}</th><th>{t('cnn.f.kind')}</th><th>{t('col.wbnum')}</th>
              {!external && <th>{t('col.company')}</th>}<th>{t('col.transport')}</th>
              <th>{t('cnn.f.payer')}</th><th>{t('cnn.f.route')}</th><th>{t('cn.f.cargo')}</th>
              <th>{t('cnn.f.weight')}</th><th>{t('cnn.f.distance')}</th><th>{t('cnn.f.trips')}</th><th>P, т·км</th><th />
            </tr></thead>
            <tbody>
              {rows.map(n => (
                <tr key={n.id}>
                  <td>{n.number ?? '—'}</td>
                  <td>{n.noteDate}</td>
                  <td>{n.kind === 2 ? t('cnn.kind2') : t('cnn.kind1')}</td>
                  <td>{external ? (n.waybillNumber ?? '—') : <Link href={`/waybills/${n.waybillId}`}>{n.waybillNumber ?? '—'}</Link>}</td>
                  {!external && <td>{n.organizationName ?? n.organizationRma}</td>}
                  <td>{n.vehicleRegNumber ?? '—'}</td>
                  <td>{n.payerName ?? '—'}</td>
                  <td>{n.senderName ?? '—'} → {n.receiverName ?? '—'}{n.kind === 2 && n.forwarderName ? ` · ${n.forwarderName}` : ''}</td>
                  <td>{n.cargoName ?? '—'}</td>
                  <td>{fmt(n.cargoWeight, 3)}</td>
                  <td>{fmt(n.distance)}</td>
                  <td>{n.kind === 2 ? '—' : n.trips}</td>
                  <td>{fmt(n.transportWork)}</td>
                  <td><button type="button" className="btn secondary" style={{ padding: '3px 8px' }} title={t('cnn.btn.print')}
                    disabled={printing === n.id} onClick={() => void print(n)}>🖨</button></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={external ? 13 : 14} style={{ textAlign: 'center', color: 'var(--muted)', padding: 24 }}>{t('cnn.empty')}</td></tr>}
            </tbody>
          </table>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '12px 16px', borderTop: '1px solid var(--line)', fontSize: 13, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{data?.totalElements ?? 0}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={page <= 0} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span>{page + 1} / {pages}</span>
          <button className="btn secondary" disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>
    </>
  );
}
