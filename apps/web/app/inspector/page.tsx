'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { wb, Waybill, STATUS_LABELS, type NeruView } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../icons';
import { QrScanner } from '../QrScanner';

function fmtDateTime(iso: string | null) {
  return iso
    ? new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
    : '—';
}

/** Итог проверки путевого листа инспектором — цвет/текст баннера. title/note — ключи i18n. */
function verdict(w: Waybill): { tone: 'ok' | 'warn' | 'stop'; title: string; note: string } {
  if (w.status === 'BLOCKED') return { tone: 'stop', title: 'insp.v.blocked.t', note: 'insp.v.blocked.n' };
  if (w.status === 'EXPIRED') return { tone: 'stop', title: 'insp.v.expired.t', note: 'insp.v.expired.n' };
  if (w.status === 'CANCELLED') return { tone: 'stop', title: 'insp.v.cancelled.t', note: 'insp.v.cancelled.n' };
  if (!w.medPassed || !w.techPassed) return { tone: 'warn', title: 'insp.v.noexam.t', note: 'insp.v.noexam.n' };
  if (['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status)) return { tone: 'ok', title: 'insp.v.ok.t', note: 'insp.v.ok.n' };
  return { tone: 'warn', title: 'insp.v.offline.t', note: 'insp.v.offline.n' };
}

const TONE_BG: Record<string, string> = { ok: 'var(--green-050)', warn: 'var(--amber-050)', stop: 'var(--red-050)' };
const TONE_FG: Record<string, string> = { ok: 'var(--green)', warn: '#a9700a', stop: 'var(--red)' };

const PER_PAGE = 10;

/**
 * Кабинет инспектора — дорожный контроль: проверка путевого листа по госномеру
 * или номеру ПЛ, вердикт о допуске, и перечень проблемных листов (заблокированные,
 * просроченные). Быстрая проверка на дороге — сканированием QR (страница /verify).
 */
export default function InspectorCabinet() {
  const { t, tType, tStatus } = useT();
  const [items, setItems] = useState<Waybill[]>([]);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [checked, setChecked] = useState<Waybill | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState('');
  const [neruPlate, setNeruPlate] = useState('');
  const [neru, setNeru] = useState<NeruView | 'none' | null>(null);
  const [neruBusy, setNeruBusy] = useState(false);
  const [pFrom, setPFrom] = useState('');
  const [pTo, setPTo] = useState('');
  const [page, setPage] = useState(1);
  const [scanning, setScanning] = useState(false);
  const router = useRouter();

  // Скан QR камерой ведёт на ту же публичную страницу проверки подписи, что и
  // сканирование телефоном (офлайн-совместимая, отдельная от архива выше consolе).
  function onQrScanned(jws: string) {
    setScanning(false);
    router.push(`/verify/${jws}`);
  }

  async function neruCheck(e: React.FormEvent) {
    e.preventDefault();
    const p = neruPlate.trim();
    if (!p) return;
    setNeruBusy(true); setNeru(null);
    try { const r = await wb.neruByPlate(p); setNeru(r ?? 'none'); }
    catch { setNeru('none'); }
    finally { setNeruBusy(false); }
  }

  useEffect(() => {
    wb.list().then(setItems).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, []);

  function runCheck(e: React.FormEvent) {
    e.preventDefault();
    const q = query.trim().toLowerCase();
    setNotFound(false); setChecked(null);
    if (!q) return;
    // Совпадение по номеру ПЛ или госномеру ТС; приоритет — активному/выданному листу.
    const matches = items
      .filter(w => (w.number ?? '').toLowerCase().includes(q) || (w.vehicleRegNumber ?? '').toLowerCase().includes(q))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    const pick = matches.find(w => ['ACTIVE', 'ISSUED', 'RETURNED'].includes(w.status)) ?? matches[0];
    if (pick) setChecked(pick); else setNotFound(true);
  }

  // Проблемные листы — заблокированные и просроченные (новые сверху), с фильтром по периоду:
  // это рабочий отчёт инспектора «что выявлено за смену/месяц», его можно выгрузить.
  const problems = useMemo(
    () => items
      .filter(w => ['BLOCKED', 'EXPIRED'].includes(w.status))
      .filter(w => {
        const d = (w.createdAt ?? '').slice(0, 10);
        if (pFrom && d && d < pFrom) return false;
        if (pTo && d && d > pTo) return false;
        return true;
      })
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    [items, pFrom, pTo],
  );

  function exportProblems() {
    const head = ['Номер ПЛ', 'Тип', 'Госномер', 'Водитель', 'Организация', 'Создан', 'Статус'];
    const rows = problems.map(w => [
      w.number ?? '', tType(w.waybillType), w.vehicleRegNumber ?? '',
      String(w.driverSnapshot?.fullName ?? w.driverRma ?? ''),
      String(w.organizationSnapshot?.name ?? w.organizationRma ?? ''),
      fmtDateTime(w.createdAt), tStatus(w.status),
    ]);
    const esc = (v: string) => `"${String(v).replace(/"/g, '""')}"`;
    const csv = '﻿' + [head, ...rows].map(r => r.map(esc).join(';')).join('\r\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `inspector_violations_${pFrom || 'all'}_${pTo || 'all'}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  const stats = useMemo(() => ({
    onLine: items.filter(w => ['ISSUED', 'ACTIVE', 'RETURNED'].includes(w.status)).length,
    blocked: items.filter(w => w.status === 'BLOCKED').length,
    expired: items.filter(w => w.status === 'EXPIRED').length,
  }), [items]);

  const v = checked ? verdict(checked) : null;

  const pages = Math.max(1, Math.ceil(problems.length / PER_PAGE));
  const view = problems.slice((page - 1) * PER_PAGE, page * PER_PAGE);

  return (
    <>
      {scanning && <QrScanner onScan={onQrScanned} onClose={() => setScanning(false)} />}
      <div className="toolbar">
        <div>
          <h1>{t('nav.inspector')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('insp.lead')}</div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {/* Консоль проверки */}
      <div className="card">
        <div className="card-h"><h2>{t('insp.check.h')}</h2></div>
        <form onSubmit={runCheck} style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div style={{ flex: 1, minWidth: 260 }}>
            <label>{t('insp.f.query')}</label>
            <input
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder={t('insp.f.query.ph')}
              style={{ width: '100%' }}
            />
          </div>
          <button className="btn" type="submit"><Icon d={P.eye} cls="" /> {t('btn.check')}</button>
          <button className="btn secondary" type="button" onClick={() => setScanning(true)}>
            <Icon d={P.scan} cls="" /> {t('insp.scan.btn')}
          </button>
        </form>
        <div className="hint" style={{ marginTop: 14, marginBottom: 0 }}>
          {t('insp.hint')}
        </div>

        {/* Результат */}
        {v && checked && (
          <div style={{ marginTop: 18 }}>
            <div
              style={{
                display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', borderRadius: 12,
                background: TONE_BG[v.tone], color: TONE_FG[v.tone], fontWeight: 700, fontSize: 16, marginBottom: 16,
              }}
            >
              <Icon d={v.tone === 'ok' ? P.check : v.tone === 'stop' ? P.shield : P.alert} cls="" />
              {t(v.title)}
              <span style={{ fontWeight: 500, fontSize: 13, color: 'var(--ink-soft)', marginLeft: 6 }}>{t(v.note)}</span>
            </div>
            <dl className="kv">
              <dt>{t('insp.wbnum')}</dt><dd><span className="number">{checked.number ?? t('common.draft')}</span></dd>
              <dt>{t('col.type')}</dt><dd>{tType(checked.waybillType)}</dd>
              <dt>{t('col.transport')}</dt><dd>{String(checked.vehicleSnapshot?.brand ?? '')} {checked.vehicleRegNumber || '—'}</dd>
              <dt>{t('col.org')}</dt><dd>{String(checked.organizationSnapshot?.name ?? checked.organizationRma ?? '—')}</dd>
              <dt>{t('col.driver')}</dt><dd>{String(checked.driverSnapshot?.fullName ?? checked.driverRma ?? '—')}</dd>
              <dt>{t('drv.validity')}</dt><dd>{fmtDateTime(checked.validFrom)} → {fmtDateTime(checked.validTo)}</dd>
              <dt>{t('insp.medtech')}</dt>
              <dd>
                <span className={`badge ${checked.medPassed ? 'green' : 'gray'}`}>Т2 {checked.medPassed ? '✓' : '…'}</span>{' '}
                <span className={`badge ${checked.techPassed ? 'green' : 'gray'}`}>Т3 {checked.techPassed ? '✓' : '…'}</span>
              </dd>
            </dl>
            <button className="btn secondary" style={{ marginTop: 14 }} onClick={() => router.push(`/waybills/${checked.id}`)}>
              {t('insp.opencard')}
            </button>
          </div>
        )}
        {notFound && (
          <div style={{ marginTop: 16, color: 'var(--muted)', fontSize: 13.5 }}>
            {t('insp.notfound.pre')} «{query}» {t('insp.notfound.post')}
          </div>
        )}
      </div>

      {/* Серверная проверка по госномеру — основной инструмент на дороге: отвечает,
          есть ли у ТС действующий лист ПРЯМО СЕЙЧАС (та же сверка, что у дорожных камер).
          В отличие от консоли выше, которая ищет по уже загруженному списку, включая архив. */}
      <div className="card">
        <div className="card-h"><h2>{t('neru.title')}</h2></div>
        <p style={{ color: 'var(--muted)', fontSize: 13, margin: '0 0 12px', maxWidth: 620 }}>{t('neru.lead')}</p>
        <form onSubmit={neruCheck} style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <div style={{ flex: 1, minWidth: 260 }}>
            <input value={neruPlate} onChange={e => setNeruPlate(e.target.value)} placeholder={t('neru.ph')} style={{ width: '100%' }} />
          </div>
          <button className="btn" type="submit" disabled={neruBusy}><Icon d={P.car} cls="" /> {t('neru.check')}</button>
        </form>
        {neru === 'none' && (
          <div style={{ marginTop: 14, padding: '12px 16px', borderRadius: 10, background: 'var(--red-050)', color: 'var(--red)', fontWeight: 600 }}>
            {t('neru.notfound')}
          </div>
        )}
        {neru && neru !== 'none' && (
          <div style={{ marginTop: 14 }}>
            <div style={{ padding: '12px 16px', borderRadius: 10, background: 'var(--green-050)', color: 'var(--green)', fontWeight: 700, marginBottom: 12 }}>
              <Icon d={P.check} cls="" style={{ width: 16, height: 16 }} /> {t('neru.found')}
            </div>
            <dl className="kv">
              <dt>{t('insp.wbnum')}</dt><dd><span className="number">{neru.number ?? t('common.draft')}</span></dd>
              <dt>{t('col.status')}</dt><dd><span className={`badge ${STATUS_LABELS[neru.status]?.color ?? 'gray'}`}>{tStatus(neru.status)}</span></dd>
              <dt>{t('col.transport')}</dt><dd>{neru.vehicleRegNumber}</dd>
              <dt>{t('col.driver')}</dt><dd>{neru.driverName ?? '—'}</dd>
              <dt>{t('drv.validity')}</dt><dd>{fmtDateTime(neru.validFrom)} → {fmtDateTime(neru.validTo)}</dd>
            </dl>
            {/* Найденный лист сразу открываем — там оформляется акт проверки или блокировка. */}
            <button className="btn secondary" style={{ marginTop: 12 }} onClick={() => router.push(`/waybills/${neru.id}`)}>
              {t('neru.open')}
            </button>
          </div>
        )}
      </div>

      {/* KPI */}
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
        <div className="kpi"><div className="k-top"><span className="k-ic ic-cyan"><Icon d={P.car} cls="" /></span></div><div className="k-label">{t('kpi.online')}</div><div className="k-value">{loading ? '—' : stats.onLine}</div></div>
        <div className="kpi"><div className="k-top"><span className="k-ic ic-red"><Icon d={P.shield} cls="" /></span></div><div className="k-label">{t('insp.kpi.blocked')}</div><div className="k-value">{loading ? '—' : stats.blocked}</div></div>
        <div className="kpi"><div className="k-top"><span className="k-ic ic-amber"><Icon d={P.alert} cls="" /></span></div><div className="k-label">{t('insp.kpi.expired')}</div><div className="k-value">{loading ? '—' : stats.expired}</div></div>
      </div>

      {/* Проблемные листы */}
      <div className="card">
        <div className="card-h">
          <h2>{t('insp.problems.h')}</h2>
          <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 8, alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <span>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('insp.period.from')}</label>
              <input type="date" value={pFrom} onChange={e => { setPFrom(e.target.value); setPage(1); }} style={{ display: 'block' }} />
            </span>
            <span>
              <label style={{ fontSize: 12, color: 'var(--muted)' }}>{t('insp.period.to')}</label>
              <input type="date" value={pTo} onChange={e => { setPTo(e.target.value); setPage(1); }} style={{ display: 'block' }} />
            </span>
            <button className="btn secondary" onClick={() => { setPFrom(''); setPTo(''); setPage(1); }}>{t('wb.resetfilters')}</button>
            <button className="btn secondary" onClick={exportProblems} disabled={problems.length === 0}>
              <Icon d={P.chart} cls="" style={{ width: 15, height: 15 }} /> {t('insp.export')}
            </button>
          </span>
        </div>
        <table>
          <thead>
            <tr><th>{t('col.number')}</th><th>{t('col.type')}</th><th>{t('col.transport')}</th><th>{t('col.driver')}</th><th>{t('col.created')}</th><th>{t('col.status')}</th></tr>
          </thead>
          <tbody>
            {view.map(w => {
              const s = STATUS_LABELS[w.status] ?? { label: w.status, color: 'gray' };
              return (
                <tr key={w.id} className="clickable" onClick={() => router.push(`/waybills/${w.id}`)}>
                  <td><span className="number">{w.number ?? t('common.draft')}</span></td>
                  <td>{tType(w.waybillType).replace(/\s*\(.*\)/, '')}</td>
                  <td>{w.vehicleRegNumber || '—'}</td>
                  <td>{String(w.driverSnapshot?.fullName ?? w.driverRma ?? '—')}</td>
                  <td>{fmtDateTime(w.createdAt)}</td>
                  <td><span className={`badge ${s.color}`}>{tStatus(w.status)}</span></td>
                </tr>
              );
            })}
            {problems.length === 0 && !loading && (
              <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)', padding: 26 }}>{t('insp.problems.empty')}</td></tr>
            )}
          </tbody>
        </table>
        {/* Пагинация */}
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 14, fontSize: 12.5, color: 'var(--muted)' }}>
          <span>{t('dict.totalrecords')}: <b style={{ color: 'var(--ink)' }}>{problems.length}</b></span>
          <span style={{ flex: 1 }} />
          <button className="btn secondary" disabled={page <= 1} onClick={() => setPage(p => p - 1)} style={{ padding: '6px 12px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
          <button className="btn secondary" disabled={page >= pages} onClick={() => setPage(p => p + 1)} style={{ padding: '6px 12px' }}>›</button>
        </div>
      </div>
    </>
  );
}
