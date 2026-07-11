'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';

/** События, по которым организация получает уведомление (справочно). */
const EVENTS = ['medrej', 'techrej', 'ready', 'blocked', 'expired'];

/** Каналы доставки уведомлений и их статус (справочно). */
const CHANNELS: { key: string; status: 'active' | 'planned' }[] = [
  { key: 'inapp', status: 'active' },
  { key: 'sms', status: 'planned' },
  { key: 'email', status: 'planned' },
  { key: 'push', status: 'planned' },
  { key: 'tg', status: 'planned' },
];

export default function NotificationsSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.notify')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setnotif.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setnotif.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        <div className="card" style={{ padding: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span className="k-ic ic-amber"><Icon d={P.bell} cls="" /></span>
            <div style={{ fontWeight: 700, fontSize: 14 }}>{t('setnotif.events.t')}</div>
          </div>
          <table>
            <tbody>
              {EVENTS.map(ev => (
                <tr key={ev}>
                  <td>{t(`setnotif.ev.${ev}`)}</td>
                  <td style={{ textAlign: 'right' }}>
                    <span className="badge green">{t('setnotif.active')}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="card" style={{ padding: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span className="k-ic ic-blue"><Icon d={P.globe} cls="" /></span>
            <div style={{ fontWeight: 700, fontSize: 14 }}>{t('setnotif.channels.t')}</div>
          </div>
          <table>
            <tbody>
              {CHANNELS.map(ch => (
                <tr key={ch.key}>
                  <td>{t(`setnotif.ch.${ch.key}`)}</td>
                  <td style={{ textAlign: 'right' }}>
                    <span className={`badge ${ch.status === 'active' ? 'green' : 'gray'}`}>
                      {ch.status === 'active' ? t('setnotif.active') : t('setnotif.planned')}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div style={{ marginTop: 14 }}>
            <Link href="/notifications" className="btn secondary" style={{ textDecoration: 'none' }}>
              <Icon d={P.bell} cls="" style={{ width: 15, height: 15 }} /> {t('setnotif.open')}
            </Link>
          </div>
        </div>
      </div>
    </>
  );
}
