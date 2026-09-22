'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { SettingsEditor } from '../SettingsEditor';

/** Ключи колонок отчётов — подсказка администратору (совпадают с DTO waybill-service и ReportsView). */
const KEYS = {
  typed: ['label', 'waybills', 'laps', 'distanceKm', 'routeDistanceKm', 'passengerTurnover', 'passengerCount', 'fuelNormLiters', 'fuelGivenLiters', 'fuelDeviationLiters', 'revenue', 'kassa', 'driverSalary'],
  regional: ['planVolumeCur', 'factVolumeCur', 'planVolumePrev', 'factVolumePrev', 'volumeDoneCurPct', 'volumeDonePrevPct', 'planRotationCur', 'factRotationCur', 'planRotationPrev', 'factRotationPrev', 'rotationDoneCurPct', 'rotationDonePrevPct'],
  regional_count: ['issuedMonth', 'issuedPrevMonth', 'issuedMonthDelta', 'issuedYtd', 'issuedYtdPrev', 'issuedYtdDelta', 'processedMonth', 'processedPrevMonth', 'processedMonthDelta', 'processedYtd', 'processedYtdPrev', 'processedYtdDelta', 'unprocessedYtd', 'vehiclesYtd', 'vehiclesMonth', 'vehiclesPrevMonth', 'vehiclesMonthPrevYear', 'vehiclesMonthDelta', 'vehiclesYoYDelta', 'cargoWaybillsTotal', 'cargoWaybillsWithConsignment'],
};

/** Настройки → Отчёты: видимые колонки и подписи типовых/сводных отчётов (MIGRATION.md 7.4 / 10.5). */
export default function ReportSettingsPage() {
  const { t } = useT();
  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.reports')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setrep.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setrep.note')}</div>
      <SettingsEditor category="reports" />

      <h2 style={{ margin: '26px 0 8px' }}>{t('setrep.keys.h')}</h2>
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {(Object.keys(KEYS) as (keyof typeof KEYS)[]).map(f => (
          <div key={f} className="card" style={{ padding: 16 }}>
            <div style={{ fontWeight: 700, fontSize: 14, marginBottom: 8 }}>{t('setrep.keys.' + f)}</div>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--muted)', lineHeight: 1.6, wordBreak: 'break-word' }}>{KEYS[f].join(', ')}</div>
          </div>
        ))}
      </div>
      <div className="hint" style={{ marginTop: 14 }}>{t('setrep.example')}</div>
    </>
  );
}
