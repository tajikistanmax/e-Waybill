'use client';

import { useCallback, useEffect, useState } from 'react';
import dynamic from 'next/dynamic';
import { md, wb, TYPE_LABELS, type LivePosition } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

const REFRESH_MS = 10000;
// «Онлайн» — свежий GPS-пинг не старше этого порога (согласовано с зелёным/жёлтым сигналом);
// иначе (нет сигнала или старее) — «офлайн».
const ONLINE_MAX_SEC = 900;
const SIG_HEX: Record<string, string> = { green: '#16a34a', amber: '#ea9615', red: '#dc2626', gray: '#94a3b8' };

// Настоящая карта (Leaflet) — только на клиенте (обращается к window), поэтому ssr:false.
const LeafletMap = dynamic(() => import('./LeafletMap'), {
  ssr: false,
  loading: () => <div style={{ height: 560, display: 'grid', placeItems: 'center', color: 'var(--muted)', border: '1px solid var(--line)', borderRadius: 14 }}>Загрузка карты…</div>,
});

function agoSec(iso: string | null): number | null {
  if (!iso) return null;
  return Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
}

/** GPS-мониторинг: живой список ТС «на линии» + карта с реальными координатами. Автообновление 10 с. */
export default function MonitoringPage() {
  const { t, tStatus } = useT();
  const [rows, setRows] = useState<LivePosition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [q, setQ] = useState('');
  const [orgFilter, setOrgFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [onlineFilter, setOnlineFilter] = useState<'' | 'online' | 'offline'>('');
  const [orgs, setOrgs] = useState<{ rma: string; name: string }[]>([]);
  const [view, setView] = useState<'list' | 'map'>('map');

  // Организации для фильтра — весь справочник (а не только ТС на линии), чтобы можно было выбрать
  // любую фирму и увидеть её ТС на карте, даже если сейчас онлайн их немного. Тенант видит только
  // свою организацию → тогда фильтр не нужен (условие рендера ниже: показываем при orgs.length > 1).
  const orgOptions = Array.from(
    new Map(orgs.map(o => [o.rma, o.name])).entries(),
  ).sort((a, b) => a[1].localeCompare(b[1], 'ru'));

  // Типы транспорта — полный список (грузовой/легковой/автобус/…), а не только присутствующие сейчас
  // на линии: пользователь должен мочь выбрать тип, даже если таких ТС пока нет онлайн.
  const typeOptions = Object.keys(TYPE_LABELS);

  const load = useCallback(async () => {
    try { setRows(await wb.gpsLive()); setUpdatedAt(new Date()); setError(''); }
    catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    load();
    const h = window.setInterval(load, REFRESH_MS);
    return () => window.clearInterval(h);
  }, [load]);

  // Справочник организаций для фильтра по компании (один раз).
  useEffect(() => {
    md.organizations()
      .then(list => setOrgs(list.map(o => ({ rma: String(o.rma), name: String(o.name ?? o.rma) }))))
      .catch(() => { /* справочник недоступен — фильтр по компании просто не покажем */ });
  }, []);

  const ago = (sec: number | null) => {
    if (sec == null) return t('mon.nosignal');
    if (sec < 60) return `${sec} ${t('mon.sec')}`;
    if (sec < 3600) return `${Math.floor(sec / 60)} ${t('mon.min')}`;
    return `${Math.floor(sec / 3600)} ${t('mon.hour')}`;
  };
  const sigColor = (sec: number | null) => sec == null ? 'gray' : sec < 120 ? 'green' : sec < 900 ? 'amber' : 'red';

  const shown = rows.filter(r => {
    if (orgFilter && r.organizationRma !== orgFilter) return false;
    if (typeFilter && r.waybillType !== typeFilter) return false;
    if (onlineFilter) {
      const sec = agoSec(r.recordedAt);
      const online = sec != null && sec < ONLINE_MAX_SEC;
      if (onlineFilter === 'online' ? !online : online) return false;
    }
    const s = q.trim().toLowerCase();
    if (!s) return true;
    return [r.vehicleRegNumber, r.number, r.driver, r.organizationName].map(x => String(x ?? '').toLowerCase()).join(' ').includes(s);
  });
  const withGps = shown.filter(r => r.lat != null).length;
  const moving = shown.filter(r => (r.speedKmh ?? 0) > 3).length;

  const kpis = [
    { label: t('mon.kpi.online'), value: shown.length, icon: P.car, cls: 'ic-blue' },
    { label: t('mon.kpi.gps'), value: withGps, icon: P.route, cls: 'ic-green' },
    { label: t('mon.moving'), value: moving, icon: P.route, cls: 'ic-cyan' },
    { label: t('mon.kpi.nosignal'), value: shown.length - withGps, icon: P.alert, cls: 'ic-amber' },
  ];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.monitoring')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('mon.lead')}</div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10, color: 'var(--muted)', fontSize: 12.5 }}>
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--green)', display: 'inline-block', boxShadow: '0 0 0 4px rgba(22,163,74,.15)' }} />
          {t('mon.live')}{updatedAt ? ` · ${t('mon.updated')} ${updatedAt.toLocaleTimeString('ru-RU')}` : ''}
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        {kpis.map(k => (
          <div className="kpi" key={k.label}>
            <div className="k-top"><span className={`k-ic ${k.cls}`}><Icon d={k.icon} cls="" /></span></div>
            <div className="k-label">{k.label}</div>
            <div className="k-value">{loading ? '—' : k.value}</div>
          </div>
        ))}
      </div>

      <div className="card">
        <div className="card-h">
          <h2>{view === 'map' ? t('mon.map.h') : t('mon.table.h')}</h2>
          <div className="seg" style={{ marginLeft: 'auto', display: 'flex', gap: 0 }}>
            <button className={`btn ${view === 'map' ? '' : 'secondary'}`} style={{ borderTopRightRadius: 0, borderBottomRightRadius: 0 }} onClick={() => setView('map')}>{t('mon.view.map')}</button>
            <button className={`btn ${view === 'list' ? '' : 'secondary'}`} style={{ borderTopLeftRadius: 0, borderBottomLeftRadius: 0 }} onClick={() => setView('list')}>{t('mon.view.list')}</button>
          </div>
          {orgOptions.length > 1 && (
            <select value={orgFilter} onChange={e => setOrgFilter(e.target.value)} style={{ maxWidth: 220 }}>
              <option value="">{t('reg.allorgs')}</option>
              {orgOptions.map(([rma, name]) => <option key={rma} value={rma}>{name}</option>)}
            </select>
          )}
          <select value={typeFilter} onChange={e => setTypeFilter(e.target.value)} style={{ maxWidth: 220 }}>
            <option value="">{t('flt.alltransporttypes')}</option>
            {typeOptions.map(type => <option key={type} value={type}>{TYPE_LABELS[type] ?? type}</option>)}
          </select>
          <select value={onlineFilter} onChange={e => setOnlineFilter(e.target.value as '' | 'online' | 'offline')} style={{ maxWidth: 150 }}>
            <option value="">{t('flt.allvehstatus')}</option>
            <option value="online">{t('flt.online')}</option>
            <option value="offline">{t('flt.offline')}</option>
          </select>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('mon.search')} style={{ maxWidth: 240 }} />
        </div>

        {view === 'map' ? (
          <>
            <LeafletMap rows={shown} t={t} tStatus={tStatus} />
            <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap', alignItems: 'center', marginTop: 12, fontSize: 12.5, color: 'var(--ink-soft)' }}>
              <span style={{ color: 'var(--muted)' }}>{t('mon.map.legend')}:</span>
              {([['green', 'mon.sig.fresh'], ['amber', 'mon.sig.stale'], ['red', 'mon.sig.old'], ['gray', 'mon.nosignal']] as const).map(([c, k]) => (
                <span key={c} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                  <span style={{ width: 11, height: 11, borderRadius: '50%', background: SIG_HEX[c], border: '2px solid #fff', boxShadow: '0 0 0 1px #e2e8f0' }} />
                  {t(k)}
                </span>
              ))}
            </div>
            <div className="hint" style={{ marginTop: 10 }}>{t('mon.map.hint')}</div>
          </>
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>{t('col.vehiclenum')}</th><th>{t('col.wbnum')}</th><th>{t('col.driver')}</th>
                  <th>{t('col.status')}</th><th>{t('mon.col.coords')}</th><th>{t('mon.col.speed')}</th><th>{t('mon.col.lastseen')}</th>
                </tr>
              </thead>
              <tbody>
                {loading && <tr><td colSpan={7} style={{ color: 'var(--muted)', padding: 18 }}>{t('mon.loading')}</td></tr>}
                {!loading && shown.map((r, i) => {
                  const sec = agoSec(r.recordedAt);
                  return (
                    <tr key={r.vehicleRegNumber + i}>
                      <td><span className="number" style={{ fontFamily: 'var(--mono)', fontWeight: 700 }}>{r.vehicleRegNumber}</span></td>
                      <td><span className="number">{r.number ?? '—'}</span></td>
                      <td>{r.driver}</td>
                      <td><span className="badge blue">{tStatus(r.status)}</span></td>
                      <td>{r.lat != null && r.lon != null ? <span style={{ fontFamily: 'var(--mono)', fontSize: 12.5 }}>{Number(r.lat).toFixed(4)}, {Number(r.lon).toFixed(4)}</span> : <span style={{ color: 'var(--muted)' }}>—</span>}</td>
                      <td>{r.speedKmh != null ? `${r.speedKmh} ${t('mon.kmh')}` : '—'}</td>
                      <td><span className={`badge ${sigColor(sec)}`}>{ago(sec)}</span></td>
                    </tr>
                  );
                })}
                {!loading && shown.length === 0 && (
                  <tr><td colSpan={7} style={{ color: 'var(--muted)', textAlign: 'center', padding: 26 }}>{q.trim() ? t('mon.search.empty') : t('mon.empty')}</td></tr>
                )}
              </tbody>
            </table>
            <div className="hint" style={{ marginTop: 12 }}>{t('mon.note')}</div>
          </>
        )}
      </div>
    </>
  );
}
