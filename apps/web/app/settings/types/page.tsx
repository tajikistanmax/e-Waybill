'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** Конфигурация типов ПЛ — зеркалит бэкенд-перечень WaybillType
 *  (legacyForm, numberCode, maxValidityDays). Справочное отображение настроек типов. */
const TYPES = [
  { code: 'WB_CAR', form: '3-С', num: '01', days: 7, pax: false, intl: false },
  { code: 'WB_TAXI', form: '3-С такси', num: '02', days: 7, pax: true, intl: false },
  { code: 'WB_MINIBUS', form: '1-А', num: '03', days: 4, pax: true, intl: false },
  { code: 'WB_BUS', form: 'Т(1-АД)', num: '04', days: 1, pax: true, intl: false },
  { code: 'WB_TROLLEYBUS', form: 'Т(1-АД)', num: '05', days: 1, pax: true, intl: false },
  { code: 'WB_TRUCK', form: '2-Б', num: '06', days: 15, pax: false, intl: false },
  { code: 'WB_TRUCK_INTL', form: '5Б-БМ', num: '07', days: 30, pax: false, intl: true },
  { code: 'WB_PAX_INTL', form: '4М-БМ', num: '08', days: 30, pax: true, intl: true },
  { code: 'WB_SPECIAL', form: 'спецтехника', num: '09', days: 7, pax: false, intl: false },
  { code: 'WB_DANGEROUS', form: 'опасные грузы', num: '10', days: 1, pax: false, intl: false },
];

export default function TypesSettingsPage() {
  const { t, tType } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.wbtypes')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('settypes.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('settypes.note')}</div>

      <div className="card">
        <table>
          <thead>
            <tr>
              <th>{t('col.type')}</th>
              <th>{t('settypes.form')}</th>
              <th>{t('settypes.natcode')}</th>
              <th>{t('settypes.validity')}</th>
              <th>{t('settypes.category')}</th>
            </tr>
          </thead>
          <tbody>
            {TYPES.map(x => (
              <tr key={x.code}>
                <td style={{ fontWeight: 600 }}>{tType(x.code)}</td>
                <td><span className="badge blue" style={{ fontFamily: 'var(--mono)' }}>{x.form}</span></td>
                <td><span className="number">{x.num}</span></td>
                <td>{x.days} {t('unit.days')}</td>
                <td style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <span className={`badge ${x.pax ? 'teal' : 'gray'}`}>{x.pax ? t('settypes.pax') : t('settypes.freight')}</span>
                  {x.intl && <span className="badge amber">{t('settypes.intl')}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
