'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

type Status = 'live' | 'partial' | 'via' | 'planned';

const STATUS_BADGE: Record<Status, string> = {
  live: 'green',
  partial: 'amber',
  via: 'blue',
  planned: 'gray',
};

/** Статусный борд интеграций платформы е-Роҳхат, сгруппированный по категориям (справочно). */
const GROUPS: { key: string; icon: string; ic: string; items: { key: string; status: Status }[] }[] = [
  {
    key: 'core',
    icon: P.globe,
    ic: 'ic-blue',
    items: [
      { key: 'unified', status: 'live' },
      { key: 'epermit', status: 'live' },
      { key: 'tax', status: 'via' },
      { key: 'police', status: 'via' },
    ],
  },
  {
    key: 'field',
    icon: P.shield,
    ic: 'ic-green',
    items: [
      { key: 'gps', status: 'live' },
      { key: 'neru', status: 'live' },
      { key: 'qr', status: 'live' },
    ],
  },
  {
    key: 'fin',
    icon: P.chart,
    ic: 'ic-amber',
    items: [
      { key: 'payment', status: 'partial' },
      { key: 'esign', status: 'partial' },
    ],
  },
  {
    key: 'msg',
    icon: P.bell,
    ic: 'ic-cyan',
    items: [
      { key: 'inapp', status: 'live' },
      { key: 'events', status: 'live' },
      { key: 'sms', status: 'planned' },
    ],
  },
  {
    key: 'ext',
    icon: P.doc,
    ic: 'ic-blue',
    items: [
      { key: 'ecmr', status: 'planned' },
      { key: 'etir', status: 'planned' },
    ],
  },
];

export default function IntegrationsSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.integrations')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setint.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setint.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {GROUPS.map(group => (
          <div key={group.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <span className={`k-ic ${group.ic}`}><Icon d={group.icon} cls="" /></span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`setint.grp.${group.key}`)}</div>
            </div>
            <table>
              <tbody>
                {group.items.map(item => (
                  <tr key={item.key}>
                    <td>{t(`setint.i.${item.key}`)}</td>
                    <td style={{ textAlign: 'right' }}>
                      <span className={`badge ${STATUS_BADGE[item.status]}`} style={{ fontSize: 11 }}>
                        {t(`setint.st.${item.status}`)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </div>
    </>
  );
}
