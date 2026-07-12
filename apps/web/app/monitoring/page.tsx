'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { wb, type LivePosition } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';

const REFRESH_MS = 10000;

// Центр отсчёта — г. Душанбе (площадь Дусти).
const CENTER = { lat: 38.5598, lon: 68.7870 };

// Область карты (фиксированный охват Душанбе и окрестностей). Метки ТС проецируются линейно
// в эту рамку — базовая карта (река/проспекты/кварталы) остаётся на месте при обновлениях.
const BBOX = { latMin: 38.505, latMax: 38.625, lonMin: 68.690, lonMax: 68.890 };
const MAP_W = 820, MAP_H = 630;
const projX = (lon: number) => (lon - BBOX.lonMin) / (BBOX.lonMax - BBOX.lonMin) * MAP_W;
const projY = (lat: number) => (BBOX.latMax - lat) / (BBOX.latMax - BBOX.latMin) * MAP_H;
// px на километр по долготе (для масштабной линейки и расстояний).
const PX_PER_KM = (1000 / (111320 * Math.cos(CENTER.lat * Math.PI / 180))) / (BBOX.lonMax - BBOX.lonMin) * MAP_W;

function agoSec(iso: string | null): number | null {
  if (!iso) return null;
  return Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
}
function distKm(lat: number, lon: number): number {
  const dx = (lon - CENTER.lon) * 111320 * Math.cos(CENTER.lat * Math.PI / 180);
  const dy = (lat - CENTER.lat) * 111320;
  return Math.sqrt(dx * dx + dy * dy) / 1000;
}

const SIG_HEX: Record<string, string> = { green: '#16a34a', amber: '#ea9615', red: '#dc2626', gray: '#94a3b8' };

/** GPS-мониторинг: живой список ТС «на линии» + карта Душанбе с реальными координатами. Автообновление 10 с. */
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
  const moving = rows.filter(r => (r.speedKmh ?? 0) > 3).length;

  const kpis = [
    { label: t('mon.kpi.online'), value: rows.length, icon: P.car, cls: 'ic-blue' },
    { label: t('mon.kpi.gps'), value: withGps, icon: P.route, cls: 'ic-green' },
    { label: t('mon.moving'), value: moving, icon: P.route, cls: 'ic-cyan' },
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
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('mon.search')} style={{ maxWidth: 280 }} />
        </div>

        {view === 'map'
          ? <CityMap rows={shown} loading={loading} sigColor={sigColor} ago={ago} tStatus={tStatus} t={t} />
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

// --------------------------------------------------------------------- Карта

type MapProps = {
  rows: LivePosition[];
  loading: boolean;
  sigColor: (sec: number | null) => string;
  ago: (sec: number | null) => string;
  tStatus: (s: string) => string;
  t: (k: string) => string;
};

/** Стилизованная карта Душанбе (самодостаточный SVG, без внешних тайлов — «без зарубежных
 *  облаков»). Метки ТС — по реальным GPS-координатам. В проде базовый слой заменяется тайлами госЦОД. */
function CityMap({ rows, loading, sigColor, ago, tStatus, t }: MapProps) {
  const [sel, setSel] = useState<string | null>(null);

  const withCoords = useMemo(() => rows.filter(r => r.lat != null && r.lon != null), [rows]);
  const noCoords = rows.filter(r => r.lat == null || r.lon == null);
  const selected = withCoords.find(r => r.vehicleRegNumber === sel) ?? null;
  const scaleKm = 2;

  if (loading) return <div style={{ color: 'var(--muted)', padding: 24 }}>{t('mon.loading')}</div>;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) 260px', gap: 16, alignItems: 'start' }}>
      <div style={{ position: 'relative', borderRadius: 14, overflow: 'hidden', border: '1px solid var(--line)', boxShadow: 'inset 0 0 0 1px rgba(255,255,255,.4)' }}>
        <svg viewBox={`0 0 ${MAP_W} ${MAP_H}`} width="100%" style={{ display: 'block' }} role="img" aria-label={t('mon.map.h')}>
          <defs>
            <linearGradient id="mapbg" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#eef4ec" />
              <stop offset="100%" stopColor="#e6eee9" />
            </linearGradient>
            <filter id="pinshadow" x="-40%" y="-40%" width="180%" height="180%">
              <feDropShadow dx="0" dy="1.2" stdDeviation="1.4" floodColor="#0f172a" floodOpacity="0.35" />
            </filter>
          </defs>

          <rect x="0" y="0" width={MAP_W} height={MAP_H} fill="url(#mapbg)" />

          {/* Кварталы (стилизованные жилые массивы) */}
          {[[70, 90, 150, 110], [250, 60, 130, 95], [600, 120, 160, 120], [120, 380, 150, 130], [560, 400, 170, 140], [330, 470, 150, 110]].map(([x, y, w, h], i) => (
            <rect key={i} x={x} y={y} width={w} height={h} rx="10" fill="#dfe8e0" opacity="0.75" />
          ))}
          {/* Парки */}
          {[[430, 250, 90, 70], [300, 150, 80, 60]].map(([x, y, w, h], i) => (
            <rect key={i} x={x} y={y} width={w} height={h} rx="14" fill="#cfe6cf" />
          ))}

          {/* Река Варзоб (север → юг, к западу от центра) */}
          <path d="M 372 -8 C 340 110, 400 210, 360 340 C 330 450, 400 540, 372 640" fill="none" stroke="#9cc6e6" strokeWidth="9" strokeLinecap="round" opacity="0.9" />
          <path d="M 372 -8 C 340 110, 400 210, 360 340 C 330 450, 400 540, 372 640" fill="none" stroke="#bfe0f4" strokeWidth="3.5" strokeLinecap="round" />

          {/* Кольцевая дорога */}
          <rect x="120" y="110" width="580" height="430" rx="80" fill="none" stroke="#cfd8d0" strokeWidth="7" />
          {/* Проспект Рудаки (С–Ю) и Исмоили Сомонӣ (З–В) — главные оси */}
          <line x1="398" y1="0" x2="398" y2={MAP_H} stroke="#d7ad5a" strokeWidth="6" opacity="0.75" />
          <line x1="0" y1="342" x2={MAP_W} y2="342" stroke="#d7ad5a" strokeWidth="6" opacity="0.75" />
          {/* Второстепенные улицы */}
          {[250, 560].map(x => <line key={`v${x}`} x1={x} y1="20" x2={x} y2={MAP_H - 20} stroke="#dbe3db" strokeWidth="3" />)}
          {[180, 490].map(y => <line key={`h${y}`} x1="20" y1={y} x2={MAP_W - 20} y2={y} stroke="#dbe3db" strokeWidth="3" />)}

          {/* Подписи районов */}
          {([['Шоҳмансур', 470, 120], ['Фирдавсӣ', 650, 300], ['Исмоили Сомонӣ', 250, 520], ['Сино', 150, 260]] as const).map(([name, x, y]) => (
            <text key={name} x={x} y={y} fill="#8aa08f" fontSize="13" fontWeight="600" opacity="0.7" textAnchor="middle">{name}</text>
          ))}

          {/* Центр — площадь Дусти */}
          <circle cx={projX(CENTER.lon)} cy={projY(CENTER.lat)} r="6" fill="#0c5c3d" />
          <circle cx={projX(CENTER.lon)} cy={projY(CENTER.lat)} r="11" fill="none" stroke="#0c5c3d" strokeWidth="1.5" opacity="0.5" />
          <text x={projX(CENTER.lon) + 14} y={projY(CENTER.lat) + 4} fill="#0c5c3d" fontSize="12.5" fontWeight="700">{t('mon.map.center')}</text>

          {/* Метки ТС */}
          {withCoords.map((r, i) => {
            const x = projX(Number(r.lon)), y = projY(Number(r.lat));
            const inside = x >= 6 && x <= MAP_W - 6 && y >= 6 && y <= MAP_H - 6;
            const cx = Math.max(10, Math.min(MAP_W - 10, x));
            const cy = Math.max(10, Math.min(MAP_H - 10, y));
            const sec = agoSec(r.recordedAt);
            const col = SIG_HEX[sigColor(sec)] ?? '#94a3b8';
            const isSel = r.vehicleRegNumber === sel;
            const fresh = sec != null && sec < 120;
            return (
              <g key={r.vehicleRegNumber + i} style={{ cursor: 'pointer' }} onClick={() => setSel(isSel ? null : r.vehicleRegNumber)}>
                {fresh && <circle cx={cx} cy={cy} r="12" fill={col} opacity="0.22"><animate attributeName="r" values="7;16;7" dur="2.4s" repeatCount="indefinite" /><animate attributeName="opacity" values="0.3;0;0.3" dur="2.4s" repeatCount="indefinite" /></circle>}
                {/* Булавка */}
                <g filter="url(#pinshadow)">
                  <circle cx={cx} cy={cy} r={isSel ? 9 : 7} fill={col} stroke="#fff" strokeWidth="2" />
                  {(r.speedKmh ?? 0) > 3 && <circle cx={cx} cy={cy} r="2.4" fill="#fff" />}
                </g>
                {/* Подпись-чип с госномером */}
                <g transform={`translate(${cx + 11}, ${cy - 9})`}>
                  <rect x="0" y="0" width={String(r.vehicleRegNumber).length * 7.4 + 12} height="18" rx="9" fill="#ffffff" stroke="#e2e8f0" opacity={isSel ? 1 : 0.92} />
                  <text x="7" y="13" fill="#0f172a" fontSize="11" fontFamily="var(--mono)" fontWeight={isSel ? 700 : 600}>{r.vehicleRegNumber}</text>
                </g>
                {!inside && <text x={cx} y={cy - 12} textAnchor="middle" fill={col} fontSize="10">↕ за картой</text>}
              </g>
            );
          })}

          {/* Масштабная линейка */}
          <g>
            <line x1="26" y1={MAP_H - 24} x2={26 + scaleKm * PX_PER_KM} y2={MAP_H - 24} stroke="#64748b" strokeWidth="2.5" />
            <line x1="26" y1={MAP_H - 28} x2="26" y2={MAP_H - 20} stroke="#64748b" strokeWidth="2.5" />
            <line x1={26 + scaleKm * PX_PER_KM} y1={MAP_H - 28} x2={26 + scaleKm * PX_PER_KM} y2={MAP_H - 20} stroke="#64748b" strokeWidth="2.5" />
            <text x={26 + scaleKm * PX_PER_KM + 7} y={MAP_H - 20} fill="#475569" fontSize="11.5" fontFamily="var(--mono)">{scaleKm} {t('unit.km')}</text>
          </g>
          {/* Компас */}
          <text x={MAP_W - 26} y="30" fill="#64748b" fontSize="13" fontWeight="700" textAnchor="middle">N</text>
          <line x1={MAP_W - 26} y1="34" x2={MAP_W - 26} y2="52" stroke="#64748b" strokeWidth="2" />
        </svg>

        {withCoords.length === 0 && (
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', background: 'rgba(255,255,255,.55)', color: 'var(--muted)', fontSize: 14 }}>
            {t('mon.empty')}
          </div>
        )}
      </div>

      {/* Боковая панель: выбранное ТС или легенда */}
      <div>
        {selected ? (
          <div className="card" style={{ margin: 0, padding: 14 }}>
            <div style={{ fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 16 }}>{selected.vehicleRegNumber}</div>
            <div style={{ color: 'var(--muted)', fontSize: 12.5, marginTop: 2 }}>{selected.number ?? '—'}</div>
            <div style={{ marginTop: 10, fontSize: 13 }}>{selected.driver}</div>
            <div style={{ marginTop: 6, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <span className="badge blue">{tStatus(selected.status)}</span>
              <span className={`badge ${(selected.speedKmh ?? 0) > 3 ? 'green' : 'gray'}`}>{(selected.speedKmh ?? 0) > 3 ? t('mon.state.moving') : t('mon.state.parked')}</span>
            </div>
            <div style={{ marginTop: 12, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              <Metric label={t('mon.col.speed')} value={selected.speedKmh != null ? `${selected.speedKmh} ${t('mon.kmh')}` : '—'} />
              <Metric label={t('mon.map.fromcenter')} value={`${distKm(Number(selected.lat), Number(selected.lon)).toFixed(1)} ${t('unit.km')}`} />
            </div>
            <div style={{ marginTop: 10, fontSize: 12, fontFamily: 'var(--mono)', color: 'var(--ink-soft)' }}>
              {Number(selected.lat).toFixed(5)}, {Number(selected.lon).toFixed(5)}
            </div>
            <div style={{ marginTop: 3, fontSize: 12, color: 'var(--muted)' }}>{t('mon.col.lastseen')}: {ago(agoSec(selected.recordedAt))}</div>
            <button className="btn secondary" style={{ marginTop: 12, width: '100%' }} onClick={() => setSel(null)}>{t('act.back')}</button>
          </div>
        ) : (
          <div className="card" style={{ margin: 0, padding: 14 }}>
            <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 10 }}>{t('mon.map.legend')}</div>
            {([['green', 'mon.sig.fresh'], ['amber', 'mon.sig.stale'], ['red', 'mon.sig.old'], ['gray', 'mon.nosignal']] as const).map(([c, k]) => (
              <div key={c} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, marginBottom: 7 }}>
                <span style={{ width: 12, height: 12, borderRadius: '50%', background: SIG_HEX[c], border: '2px solid #fff', boxShadow: '0 0 0 1px #e2e8f0', flex: '0 0 auto' }} />
                {t(k)}
              </div>
            ))}
            <div style={{ borderTop: '1px solid var(--line)', margin: '10px 0', paddingTop: 10, fontSize: 12.5, color: 'var(--ink-soft)' }}>
              {t('mon.map.count')}: <b>{withCoords.length}</b>
            </div>
            <div className="hint" style={{ fontSize: 11.5 }}>{t('mon.map.hint')}</div>
          </div>
        )}
        {noCoords.length > 0 && (
          <div style={{ marginTop: 10, fontSize: 12, color: 'var(--muted)' }}>{t('mon.map.nocoords')}: {noCoords.length}</div>
        )}
      </div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ background: 'var(--bg-soft, #f4f7fb)', borderRadius: 9, padding: '8px 10px' }}>
      <div style={{ fontSize: 10.5, color: 'var(--muted)' }}>{label}</div>
      <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--ink)', marginTop: 2 }}>{value}</div>
    </div>
  );
}
