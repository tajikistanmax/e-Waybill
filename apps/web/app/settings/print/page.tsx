'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { Icon, P } from '../../icons';
import { SettingsEditor } from '../SettingsEditor';

/** Печатные формы путевого листа (справочно). */
const FORMS: { key: string; icon: keyof typeof P; ic: string; status: 'on' | 'planned' }[] = [
  { key: 'form', icon: 'doc', ic: 'ic-blue', status: 'on' },
  { key: 'pdf', icon: 'docActive', ic: 'ic-cyan', status: 'on' },
  { key: 'tpl', icon: 'book', ic: 'ic-amber', status: 'planned' },
  { key: 'wm', icon: 'shield', ic: 'ic-purple', status: 'planned' },
];

export default function PrintSettingsPage() {
  const { t } = useT();

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('set.mod.print')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('setprint.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('setprint.note')}</div>

      <h2 style={{ margin: '0 0 6px' }}>{t('setprint.options.h')}</h2>
      <div className="page-lead" style={{ marginTop: 0, marginBottom: 14 }}>{t('setprint.options.lead')}</div>
      <SettingsEditor category="print" />

      <h2 style={{ margin: '26px 0 12px' }}>{t('setprint.forms.h')}</h2>
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {FORMS.map(f => (
          <div key={f.key} className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              <span className={`k-ic ${f.ic}`}><Icon d={P[f.icon]} cls="" /></span>
              <div style={{ fontWeight: 700, fontSize: 14 }}>{t(`setprint.${f.key}.t`)}</div>
              <span
                className={`badge ${f.status === 'on' ? 'green' : 'gray'}`}
                style={{ marginLeft: 'auto' }}
              >
                {t(f.status === 'on' ? 'setprint.on' : 'setprint.planned')}
              </span>
            </div>
            <div style={{ color: 'var(--muted)', fontSize: 13, lineHeight: 1.5 }}>{t(`setprint.${f.key}.d`)}</div>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 18 }}>
        <Link href="/waybills" className="btn secondary" style={{ textDecoration: 'none' }}>
          <Icon d={P.doc} cls="" style={{ width: 15, height: 15 }} /> {t('setprint.open')}
        </Link>
      </div>
    </>
  );
}
