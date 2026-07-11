'use client';

import { useCallback, useEffect, useState } from 'react';
import { md } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

type Row = Record<string, unknown>;

/** Транспорт и водители организации: серверный поиск (для автопарков в тысячи записей)
 *  и добавление по госномеру/ИНН (регистрация из единой платформы) — диспетчер/админ компании. */
export default function FleetPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const canAdd = roles.includes('DISPATCHER') || roles.includes('COMPANY_ADMIN') || roles.includes('SYSTEM_ADMIN');

  const [tab, setTab] = useState<'vehicles' | 'drivers'>('vehicles');
  const [orgRma, setOrgRma] = useState('');
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [addVal, setAddVal] = useState('');
  const [addBusy, setAddBusy] = useState(false);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => { md.organizations().then(l => { if (l.length) setOrgRma(String(l[0].rma)); }).catch(() => {}); }, []);

  const load = useCallback(async (query: string, which: 'vehicles' | 'drivers') => {
    setLoading(true); setErr('');
    try {
      const list = which === 'vehicles' ? await md.searchVehicles('', query, 50) : await md.searchDrivers('', query, 50);
      setRows(list);
    } catch (e) { setErr((e as Error).message); setRows([]); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const h = window.setTimeout(() => load(q, tab), 250);
    return () => window.clearTimeout(h);
  }, [q, tab, load]);

  async function add() {
    if (!addVal.trim() || !orgRma) return;
    setAddBusy(true); setErr(''); setMsg('');
    try {
      if (tab === 'vehicles') await md.syncVehicle({ registrationNumber: addVal.trim().toUpperCase(), organizationRma: orgRma });
      else await md.syncDriver({ inn: addVal.trim(), organizationRma: orgRma });
      setMsg(t('fleet.added'));
      setAdding(false); setAddVal('');
      load(q, tab);
    } catch (e) { setErr((e as Error).message); }
    finally { setAddBusy(false); }
  }

  function switchTab(which: 'vehicles' | 'drivers') {
    setTab(which); setQ(''); setRows([]); setAdding(false); setAddVal(''); setMsg(''); setErr('');
  }

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.fleet')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('fleet.lead')}</div>
        </div>
        {canAdd && (
          <button className="btn" style={{ marginLeft: 'auto' }} onClick={() => { setAdding(a => !a); setMsg(''); setErr(''); }}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} />{' '}
            {tab === 'vehicles' ? t('fleet.add.vehicle') : t('fleet.add.driver')}
          </button>
        )}
      </div>

      {/* Вкладки */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <button className={`btn ${tab === 'vehicles' ? 'primary' : 'secondary'}`} onClick={() => switchTab('vehicles')}>
          <Icon d={P.car} cls="" style={{ width: 15, height: 15 }} /> {t('fleet.tab.vehicles')}
        </button>
        <button className={`btn ${tab === 'drivers' ? 'primary' : 'secondary'}`} onClick={() => switchTab('drivers')}>
          <Icon d={P.user} cls="" style={{ width: 15, height: 15 }} /> {t('fleet.tab.drivers')}
        </button>
      </div>

      {/* Форма добавления (по госномеру/ИНН → регистрация из единой платформы) */}
      {canAdd && adding && (
        <div className="card" style={{ padding: 16, marginBottom: 16 }}>
          <label>{tab === 'vehicles' ? t('fleet.add.placeholder.vehicle') : t('fleet.add.placeholder.driver')}</label>
          <div style={{ display: 'flex', gap: 10, marginTop: 6 }}>
            <input value={addVal} onChange={e => setAddVal(e.target.value)} autoFocus
              placeholder={tab === 'vehicles' ? '0114TJ01' : '123456789'}
              onKeyDown={e => { if (e.key === 'Enter') add(); }} style={{ flex: 1 }} />
            <button className="btn primary" disabled={addBusy || !addVal.trim()} onClick={add}>{addBusy ? '…' : t('fleet.save')}</button>
            <button className="btn secondary" onClick={() => { setAdding(false); setAddVal(''); }}>{t('fleet.cancel')}</button>
          </div>
          <div className="hint" style={{ marginTop: 10 }}>{t('fleet.add.hint')}</div>
        </div>
      )}
      {msg && <div className="hint" style={{ marginBottom: 12, color: 'var(--green-700, #15803d)' }}>{msg}</div>}
      {err && <div className="hint" style={{ marginBottom: 12, color: 'var(--red, #dc2626)', borderColor: 'var(--red, #dc2626)' }}>{err}</div>}

      {/* Поиск */}
      <div className="card" style={{ padding: 16 }}>
        <input value={q} onChange={e => setQ(e.target.value)}
          placeholder={tab === 'vehicles' ? t('fleet.search.vehicle') : t('fleet.search.driver')} style={{ marginBottom: 12 }} />
        <table>
          <thead>
            {tab === 'vehicles' ? (
              <tr><th>{t('fleet.col.plate')}</th><th>{t('fleet.col.brand')}</th><th>{t('fleet.col.type')}</th><th>{t('fleet.col.tech')}</th></tr>
            ) : (
              <tr><th>{t('fleet.col.name')}</th><th>{t('fleet.col.inn')}</th><th>{t('fleet.col.cat')}</th><th>{t('fleet.col.license')}</th></tr>
            )}
          </thead>
          <tbody>
            {loading && <tr><td colSpan={4} style={{ color: 'var(--muted)' }}>{t('fleet.loading')}</td></tr>}
            {!loading && rows.length === 0 && <tr><td colSpan={4} style={{ color: 'var(--muted)' }}>{t('fleet.empty')}</td></tr>}
            {!loading && tab === 'vehicles' && rows.map((v, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600, fontFamily: 'var(--mono)' }}>{String(v.registrationNumber ?? '')}</td>
                <td>{String(v.brand ?? '—')}</td>
                <td>{String(v.transportType ?? '—')}</td>
                <td>{v.techInspectionValidTo ? String(v.techInspectionValidTo) : '—'}</td>
              </tr>
            ))}
            {!loading && tab === 'drivers' && rows.map((d, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>{String(d.fullName ?? '')}</td>
                <td style={{ fontFamily: 'var(--mono)' }}>{String(d.rma ?? '')}</td>
                <td>{String(d.licenseCategories ?? '—')}</td>
                <td>{d.licenseValidTo ? String(d.licenseValidTo) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="hint" style={{ marginTop: 10 }}>{t('fleet.note')}</div>
      </div>
    </>
  );
}
