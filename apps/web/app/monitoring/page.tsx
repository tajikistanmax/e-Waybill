'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { wb, type LivePosition } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

const REFRESH_MS = 10000;

// Центр отсчёта радара — г. Душанбе (широта/долгота площади Дусти).
const CENTER = { lat: 38.5598, lon: 68.7870 };

function agoSec(iso: string | null): number | null {
  if (!iso) return null;
  return Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
}

/** GPS-мониторинг: живой список ТС «на линии» + радар с реальными координатами. Автообновление раз в 10 с. */
export default function MonitoringPage() {
  const { t, tStatus } = useT();
  const [rows, setRows] = useState<LivePosition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [q, setQ] = useState('');
  const [view, setView] = useState<'list' | 'map'>('map');

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

  const ago = (sec: number | null) => {
    if (sec == null) return t('mon.nosignal');
    if (sec < 60) return `${sec} ${t('mon.sec')}`;
    if (sec < 3600) return `${Math.floor(sec / 60)} ${t('mon.min')}`;
    return `${Math.floor(sec / 3600)} ${t('mon.hour')}`;
  };
  const sigColor = (sec: number | null) => sec == null ? 'gray' : sec < 120 ? 'green' : sec < 900 ? 'amber' : 'red';

  const shown = rows.filter(r => {
    const s = q.trim().toLowerCase();
    if (!s) return true;
    return [r.vehicleRegNumber, r.number, r.driver].map(x => String(x ?? '').toLowerCase()).join(' ').includes(s);
  });
  const withGps = rows.filter(r => r.lat != null).length;

  const kpis = [
    { label: t('mon.kpi.online'), value: rows.length, icon: P.car, cls: 'ic-blue' },
    { label: t('mon.kpi.gps'), value: withGps, icon: P.route, cls: 'ic-green' },
    { label: t('mon.kpi.nosignal'), value: rows.length - withGps, icon: P.alert, cls: 'ic-amber' },
  ];

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.monitoring')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('mon.lead')}</div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10, color: 'var(--muted)', fontSize: 12.5 }}>
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--green)', display: 'inline-block' }} />
          {t('mon.live')}{updatedAt ? ` · ${t('mon.updated')} ${updatedAt.toLocaleTimeString('ru-RU')}` : ''}
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
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
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('mon.search')} style={{ maxWidth: 280 }} />
        </div>

        {view === 'map'
          ? <RadarMap rows={shown} loading={loading} sigColor={sigColor} ago={ago} tStatus={tStatus} t={t} />
          : (
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

// --------------------------------------------------------------------- Радар

type RadarProps = {
  rows: LivePosition[];
  loading: boolean;
  sigColor: (sec: number | null) => string;
  ago: (sec: number | null) => string;
  tStatus: (s: string) => string;
  t: (k: string) => string;
};

const SIG_HEX: Record<string, string> = { green: '#16a34a', amber: '#ea9615', red: '#dc2626', gray: '#94a3b8' };

/** Самодостаточный SVG-радар: реальные GPS-координаты ТС относительно центра Душанбе.
 *  Без внешних тайлов (ограничение «без зарубежных облаков»). Продакшн — тайлы госЦОД. */
function RadarMap({ rows, loading, sigColor, ago, tStatus, t }: RadarProps) {
  const [sel, setSel] = useState<string | null>(null);

  const withCoords = useMemo(() => rows.filter(r => r.lat != null && r.lon != null), [rows]);
  const noCoords = rows.filter(r => r.lat == null || r.lon == null);

  // Геопроекция: локальная равнопромежуточная относительно центра, масштаб — по самой дальней точке.
  const geo = useMemo(() => {
    const W = 720, H = 560, mid = { x: W / 2, y: H / 2 }, R = 250, pad = 34;
    const mLat = 111320;                                   // м на градус широты
    const mLon = 111320 * Math.cos(CENTER.lat * Math.PI / 180); // м на градус долготы (сжатие)
    const dist = (r: LivePosition) => {
      const dx = (Number(r.lon) - CENTER.lon) * mLon;
      const dy = (Number(r.lat) - CENTER.lat) * mLat;
      return Math.sqrt(dx * dx + dy * dy);
    };
    const maxDist = Math.max(2500, ...withCoords.map(dist)); // м; пол 2.5 км, чтобы одна близкая точка не «зумилась»
    const s = (R - pad) / maxDist;                          // svg-единиц на метр
    const project = (lat: number, lon: number) => ({
      x: mid.x + (lon - CENTER.lon) * mLon * s,
      y: mid.y - (lat - CENTER.lat) * mLat * s,
    });
    // Кольца дальности каждые 5 км, пока покрывают точки.
    const ringStepM = maxDist > 12000 ? 10000 : 5000;
    const rings: number[] = [];
    for (let d = ringStepM; d <= maxDist * 1.08; d += ringStepM) rings.push(d);
    return { W, H, mid, R, s, project, rings, dist };
  }, [withCoords]);

  const selected = withCoords.find(r => r.vehicleRegNumber === sel) ?? null;

  if (loading) return <div style={{ color: 'var(--muted)', padding: 24 }}>{t('mon.loading')}</div>;
  if (withCoords.length === 0) {
    return (
      <div style={{ color: 'var(--muted)', textAlign: 'center', padding: 40 }}>
        {t('mon.empty')}
        {noCoords.length > 0 && <div style={{ marginTop: 8, fontSize: 12.5 }}>{t('mon.map.nocoords')}: {noCoords.length}</div>}
      </div>
    );
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 250px', gap: 16, alignItems: 'start' }}>
      <div style={{ position: 'relative', borderRadius: 14, overflow: 'hidden', background: 'var(--radar-bg, #0b1220)' }}>
        <svg viewBox={`0 0 ${geo.W} ${geo.H}`} width="100%" style={{ display: 'block' }} role="img" aria-label={t('mon.map.h')}>
          <defs>
            <radialGradient id="radar-glow" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="#12324a" />
              <stop offset="100%" stopColor="#0b1220" />
            </radialGradient>
          </defs>
          <rect x="0" y="0" width={geo.W} height={geo.H} fill="url(#radar-glow)" />

          {/* Оси-перекрестье */}
          <line x1={geo.mid.x} y1="14" x2={geo.mid.x} y2={geo.H - 14} stroke="#1e3a52" strokeWidth="1" />
          <line x1="14" y1={geo.mid.y} x2={geo.W - 14} y2={geo.mid.y} stroke="#1e3a52" strokeWidth="1" />

          {/* Кольца дальности + подписи км */}
          {geo.rings.map(d => {
            const r = d * geo.s;
            return (
              <g key={d}>
                <circle cx={geo.mid.x} cy={geo.mid.y} r={r} fill="none" stroke="#1e3a52" strokeWidth="1" strokeDasharray="3 4" />
                <text x={geo.mid.x + 4} y={geo.mid.y - r - 3} fill="#4d6b86" fontSize="10.5" fontFamily="var(--mono)">{Math.round(d / 1000)} {t('unit.km')}</text>
              </g>
            );
          })}

          {/* Север */}
          <text x={geo.mid.x} y="12" fill="#6b8aa6" fontSize="11" textAnchor="middle" fontWeight="700">N</text>

          {/* Центр — Душанбе */}
          <circle cx={geo.mid.x} cy={geo.mid.y} r="4.5" fill="#38bdf8" />
          <text x={geo.mid.x + 8} y={geo.mid.y + 4} fill="#7dd3fc" fontSize="11.5" fontWeight="600">{t('mon.map.center')}</text>

          {/* Точки ТС */}
          {withCoords.map((r, i) => {
            const p = geo.project(Number(r.lat), Number(r.lon));
            const sec = agoSecLocal(r.recordedAt);
            const col = SIG_HEX[sigColor(sec)] ?? '#94a3b8';
            const isSel = r.vehicleRegNumber === sel;
            const fresh = sec != null && sec < 120;
            return (
              <g key={r.vehicleRegNumber + i} style={{ cursor: 'pointer' }} onClick={() => setSel(isSel ? null : r.vehicleRegNumber)}>
                {fresh && <circle cx={p.x} cy={p.y} r="10" fill={col} opacity="0.25"><animate attributeName="r" values="6;13;6" dur="2.2s" repeatCount="indefinite" /><animate attributeName="opacity" values="0.35;0;0.35" dur="2.2s" repeatCount="indefinite" /></circle>}
                <circle cx={p.x} cy={p.y} r={isSel ? 7 : 5} fill={col} stroke="#0b1220" strokeWidth="1.5" />
                <text x={p.x + 9} y={p.y + 4} fill="#cfe3f5" fontSize="11" fontFamily="var(--mono)" fontWeight={isSel ? 700 : 500}>{r.vehicleRegNumber}</text>
              </g>
            );
          })}

          {/* Масштабная линейка */}
          <ScaleBar geo={geo} t={t} />
        </svg>
      </div>

      {/* Боковая панель: выбранное ТС или легенда */}
      <div>
        {selected ? (
          <div className="card" style={{ margin: 0, padding: 14 }}>
            <div style={{ fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 15 }}>{selected.vehicleRegNumber}</div>
            <div style={{ color: 'var(--muted)', fontSize: 12.5, marginTop: 2 }}>{selected.number ?? '—'}</div>
            <div style={{ marginTop: 10, fontSize: 13 }}>{selected.driver}</div>
            <div style={{ marginTop: 6 }}><span className="badge blue">{tStatus(selected.status)}</span></div>
            <div style={{ marginTop: 10, fontSize: 12.5, fontFamily: 'var(--mono)', color: 'var(--ink-soft)' }}>
              {Number(selected.lat).toFixed(5)}, {Number(selected.lon).toFixed(5)}
            </div>
            <div style={{ marginTop: 4, fontSize: 12.5, color: 'var(--muted)' }}>
              {selected.speedKmh != null ? `${selected.speedKmh} ${t('mon.kmh')} · ` : ''}{ago(agoSecLocal(selected.recordedAt))}
            </div>
            <div style={{ marginTop: 4, fontSize: 12, color: 'var(--muted)' }}>
              {(geo.dist(selected) / 1000).toFixed(1)} {t('unit.km')} {t('mon.map.fromcenter')}
            </div>
            <button className="btn secondary" style={{ marginTop: 12, width: '100%' }} onClick={() => setSel(null)}>{t('act.back')}</button>
          </div>
        ) : (
          <div className="card" style={{ margin: 0, padding: 14 }}>
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 10 }}>{t('mon.map.legend')}</div>
            {([['green', 'mon.sig.fresh'], ['amber', 'mon.sig.stale'], ['red', 'mon.sig.old'], ['gray', 'mon.nosignal']] as const).map(([c, k]) => (
              <div key={c} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, marginBottom: 7 }}>
                <span style={{ width: 11, height: 11, borderRadius: '50%', background: SIG_HEX[c], flex: '0 0 auto' }} />
                {t(k)}
              </div>
            ))}
            <div className="hint" style={{ marginTop: 8, fontSize: 11.5 }}>{t('mon.map.hint')}</div>
          </div>
        )}
        {noCoords.length > 0 && (
          <div style={{ marginTop: 10, fontSize: 12, color: 'var(--muted)' }}>{t('mon.map.nocoords')}: {noCoords.length}</div>
        )}
      </div>
    </div>
  );
}

function agoSecLocal(iso: string | null): number | null {
  if (!iso) return null;
  return Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
}

/** Масштабная линейка: «красивое» число километров, укладывающееся в ~1/4 радиуса. */
function ScaleBar({ geo, t }: { geo: { mid: { x: number; y: number }; R: number; s: number; H: number }; t: (k: string) => string }) {
  const targetM = (geo.R / 2) / geo.s;                 // цель — половина радиуса в метрах
  const nice = [1000, 2000, 5000, 10000, 20000, 50000];
  const m = nice.reduce((a, b) => (Math.abs(b - targetM) < Math.abs(a - targetM) ? b : a), nice[0]);
  const len = m * geo.s;
  const x0 = 24, y0 = geo.H - 22;
  return (
    <g>
      <line x1={x0} y1={y0} x2={x0 + len} y2={y0} stroke="#6b8aa6" strokeWidth="2" />
      <line x1={x0} y1={y0 - 4} x2={x0} y2={y0 + 4} stroke="#6b8aa6" strokeWidth="2" />
      <line x1={x0 + len} y1={y0 - 4} x2={x0 + len} y2={y0 + 4} stroke="#6b8aa6" strokeWidth="2" />
      <text x={x0 + len + 6} y={y0 + 4} fill="#8fb0cc" fontSize="11" fontFamily="var(--mono)">{m / 1000} {t('unit.km')}</text>
    </g>
  );
}
