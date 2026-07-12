'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { SettingsEditor } from '../SettingsEditor';

/**
 * Уведомления (§29): администратор включает/выключает in-app уведомление организации по типу
 * события ПЛ (реальные тумблеры — настройки категории notifications; читает waybill-service).
 * Доставка — только внутри платформы (внешние каналы SMS/push не используются по решению заказчика).
 */
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

      <div className="hint" style={{ marginBottom: 18 }}>{t('setnotif.note2')}</div>

      <h2 style={{ margin: '4px 0 6px' }}>{t('setnotif.events.h')}</h2>
      <div className="page-lead" style={{ marginTop: 0, marginBottom: 14 }}>{t('setnotif.events.lead')}</div>
      <SettingsEditor category="notifications" />

      <div className="card" style={{ padding: 16, marginTop: 24, maxWidth: 640 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <span className="k-ic ic-green"><Icon d={P.bell} cls="" /></span>
          <div style={{ fontWeight: 700, fontSize: 14 }}>{t('setnotif.delivery.t')}</div>
        </div>
        <div style={{ color: 'var(--muted)', fontSize: 13, lineHeight: 1.5, marginBottom: 12 }}>{t('setnotif.delivery.d')}</div>
        <Link href="/notifications" className="btn secondary" style={{ textDecoration: 'none' }}>
          <Icon d={P.bell} cls="" style={{ width: 15, height: 15 }} /> {t('setnotif.open')}
        </Link>
      </div>
    </>
  );
}
