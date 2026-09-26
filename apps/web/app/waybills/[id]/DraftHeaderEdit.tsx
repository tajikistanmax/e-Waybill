'use client';

import { useEffect, useState } from 'react';
import { md, wb, type Waybill } from '@/lib/api';
import { useT } from '@/lib/i18n';

/**
 * Правка шапки черновика (сверка 25.09, A18): маршрут, график и особые отметки до подписи Т1.
 * Раньше ошибку в шапке можно было исправить только аннулированием и оформлением заново.
 * Маршрут у маршрутных форм — из справочника маршрутов организации, как в мастере.
 */
export default function DraftHeaderEdit({ w, act }: { w: Waybill; act: (label: string, fn: () => Promise<unknown>) => Promise<void> }) {
  const { t } = useT();
  const td = (w.typeData ?? {}) as Record<string, unknown>;
  const routeForm = ['WB_BUS', 'WB_TROLLEYBUS', 'WB_MINIBUS'].includes(w.waybillType)
    || (['WB_CAR', 'WB_TAXI'].includes(w.waybillType) && td.serviceKind === 'ROUTE');
  const hasRoute = !['WB_TRUCK', 'WB_TRUCK_INTL', 'WB_DANGEROUS'].includes(w.waybillType);
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ route: w.route ?? '', schedule: w.schedule ?? '', specialMark: w.specialMark ?? '' });
  const [routes, setRoutes] = useState<{ number: string; name: string }[]>([]);

  useEffect(() => {
    if (!open || !routeForm) return;
    md.routes().then(list => setRoutes(list.filter(r => String(r.organizationRma) === w.organizationRma)
      .sort((a, b) => String(a.number).localeCompare(String(b.number), 'ru', { numeric: true }))))
      .catch(() => setRoutes([]));
  }, [open, routeForm, w.organizationRma]);

  if (!open) {
    return <button type="button" className="btn secondary" onClick={() => setOpen(true)} data-testid="draft-edit">{t('wb.draft.edit')}</button>;
  }
  return (
    <form className="grid" style={{ marginTop: 12 }} data-testid="draft-edit-form"
      onSubmit={async e => {
        e.preventDefault();
        await act(t('wb.draft.saved'), () => wb.patch(`/${w.id}`, {
          ...(hasRoute ? { route: f.route } : {}), schedule: f.schedule, specialMark: f.specialMark,
        }));
        setOpen(false);
      }}>
      {hasRoute && (
        <div><label>{t('wb.f.route')}{routeForm ? ' *' : ''}</label>
          {routeForm && routes.length > 0 ? (
            <select required aria-label={t('wb.f.route')} value={f.route} onChange={e => setF({ ...f, route: e.target.value })}>
              <option value="">{t('wb.route.pick')}</option>
              {f.route && !routes.some(r => r.number === f.route) && <option value={f.route}>{f.route}</option>}
              {routes.map(r => <option key={r.number} value={r.number}>{r.number} — {r.name}</option>)}
            </select>
          ) : (
            <input required={routeForm} aria-label={t('wb.f.route')} value={f.route} onChange={e => setF({ ...f, route: e.target.value })} />
          )}
        </div>
      )}
      <div><label>{t('wb.f.schedule')}</label>
        <input aria-label={t('wb.f.schedule')} value={f.schedule} onChange={e => setF({ ...f, schedule: e.target.value })} /></div>
      <div className="full"><label>{t('wb.specialmark')}</label>
        <textarea aria-label={t('wb.specialmark')} maxLength={500} rows={2} value={f.specialMark}
          onChange={e => setF({ ...f, specialMark: e.target.value })} /></div>
      <div className="full" style={{ display: 'flex', gap: 8 }}>
        <button className="btn" type="submit">{t('btn.save')}</button>
        <button className="btn secondary" type="button" onClick={() => setOpen(false)}>{t('btn.cancel')}</button>
      </div>
    </form>
  );
}
