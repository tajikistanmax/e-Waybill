'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { md, wb, type MdOps, type WbOps } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
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
  const { roles } = useAuth();
  const isAdmin = roles.includes('SYSTEM_ADMIN');
  // Живая конфигурация стенда (ops/overview обоих сервисов) — только SYSTEM_ADMIN.
  const [mdOps, setMdOps] = useState<MdOps | null>(null);
  const [wbOps, setWbOps] = useState<WbOps | null>(null);
  const [opsErr, setOpsErr] = useState('');

  useEffect(() => {
    if (!isAdmin) return;
    md.ops().then(setMdOps).catch(e => setOpsErr((e as Error).message));
    wb.ops().then(setWbOps).catch(e => setOpsErr((e as Error).message));
  }, [isAdmin]);

  const onOff = (v: boolean, onKey = 'setint.v.on', offKey = 'setint.v.off') =>
    <span className={`badge ${v ? 'green' : 'gray'}`}>{t(v ? onKey : offKey)}</span>;

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

      {isAdmin && (
        <div className="card" style={{ padding: 16, marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span className="k-ic ic-green"><Icon d={P.settings} cls="" /></span>
            <div style={{ fontWeight: 700, fontSize: 14 }}>{t('setint.live.h')}</div>
          </div>
          {opsErr && <div className="error" style={{ marginBottom: 10 }}>{opsErr}</div>}
          <table>
            <tbody>
              <tr><td>{t('setint.live.unified')}</td><td style={{ textAlign: 'right' }}>
                {mdOps ? (mdOps.unifiedPlatformMode === 'http'
                  ? <span className="badge green">{t('setint.v.http')}</span>
                  : <span className="badge amber">{t('setint.v.stub')}</span>) : '…'}
              </td></tr>
              <tr><td>{t('setint.live.payment')}</td><td style={{ textAlign: 'right' }}>{wbOps ? onOff(wbOps.paymentEnabled) : '…'}</td></tr>
              <tr><td>{t('setint.live.aggregator')}</td><td style={{ textAlign: 'right' }}>
                {wbOps ? (wbOps.aggregatorOpen
                  ? <span className="badge amber">{t('setint.v.aggopen')}</span>
                  : <span className="badge green">{t('setint.v.aggclosed')}</span>) : '…'}
              </td></tr>
              <tr><td>{t('setint.live.signing')}</td><td style={{ textAlign: 'right' }}>
                {wbOps ? (wbOps.signingMode === 'cades'
                  ? <span className="badge green">CAdES</span>
                  : <span className="badge amber">{t('setint.v.stubsign')}</span>) : '…'}
              </td></tr>
              <tr><td>{t('setint.live.kafka')}</td><td style={{ textAlign: 'right', fontFamily: 'var(--mono)', fontSize: 12 }}>
                {wbOps ? `${wbOps.kafkaBootstrap} · ${wbOps.eventsTopic}` : '…'}
              </td></tr>
            </tbody>
          </table>
        </div>
      )}

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
