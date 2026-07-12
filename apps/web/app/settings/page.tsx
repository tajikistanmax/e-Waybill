'use client';

import Link from 'next/link';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../icons';

type St = 'done' | 'partial' | 'planned' | 'info';

/** Разделы административной подсистемы «Настройки» (по ТЗ spec/ТЗ-ЕДИНОЕ-DTS.md §29).
 *  st: done — доступно, partial — частично (есть основа), planned — планируется.
 *  href — куда ведёт уже существующая настройка. */
const MODULES: { key: string; icon: string; cls: string; st: St; href?: string; admin?: boolean }[] = [
  { key: 'rules', icon: P.settings, cls: 'ic-green', st: 'done', href: '/settings/policies' },
  { key: 'general', icon: P.help, cls: 'ic-blue', st: 'done', href: '/settings/general' },
  { key: 'branding', icon: P.building, cls: 'ic-purple', st: 'done', href: '/settings/branding', admin: true },
  { key: 'org', icon: P.building, cls: 'ic-blue', st: 'partial', href: '/company' },
  { key: 'wbtypes', icon: P.doc, cls: 'ic-cyan', st: 'info', href: '/settings/types' },
  { key: 'fields', icon: P.book, cls: 'ic-cyan', st: 'done', href: '/settings/fields' },
  { key: 'routes', icon: P.route, cls: 'ic-cyan', st: 'partial', href: '/dictionaries' },
  { key: 'drivers', icon: P.user, cls: 'ic-blue', st: 'partial', href: '/registry' },
  { key: 'vehicles', icon: P.car, cls: 'ic-blue', st: 'partial', href: '/registry' },
  { key: 'med', icon: P.med, cls: 'ic-green', st: 'partial', href: '/med' },
  { key: 'tech', icon: P.wrench, cls: 'ic-amber', st: 'partial', href: '/tech' },
  { key: 'payment', icon: P.chart, cls: 'ic-green', st: 'partial', href: '/dictionaries' },
  { key: 'numbering', icon: P.docActive, cls: 'ic-cyan', st: 'info', href: '/settings/numbering' },
  { key: 'statuses', icon: P.route, cls: 'ic-cyan', st: 'info', href: '/settings/statuses' },
  { key: 'roles', icon: P.users, cls: 'ic-blue', st: 'done', href: '/settings/roles', admin: true },
  { key: 'notify', icon: P.bell, cls: 'ic-amber', st: 'info', href: '/settings/notifications' },
  { key: 'integrations', icon: P.globe, cls: 'ic-blue', st: 'info', href: '/settings/integrations' },
  { key: 'security', icon: P.shield, cls: 'ic-red', st: 'done', href: '/settings/security' },
  { key: 'print', icon: P.doc, cls: 'ic-cyan', st: 'partial', href: '/settings/print' },
  { key: 'dictionaries', icon: P.book, cls: 'ic-blue', st: 'done', href: '/dictionaries' },
  { key: 'classifiers', icon: P.globe, cls: 'ic-cyan', st: 'done', href: '/settings/classifiers' },
  { key: 'expiry', icon: P.alert, cls: 'ic-amber', st: 'done', href: '/settings/expiry' },
  { key: 'audit', icon: P.eye, cls: 'ic-amber', st: 'done', href: '/settings/audit', admin: true },
  { key: 'backup', icon: P.shield, cls: 'ic-blue', st: 'info', href: '/settings/backup' },
  { key: 'performance', icon: P.chart, cls: 'ic-purple', st: 'info', href: '/settings/performance' },
  { key: 'interface', icon: P.globe, cls: 'ic-cyan', st: 'partial', href: '/settings/interface' },
];

export default function SettingsPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const sysAdmin = roles.includes('SYSTEM_ADMIN');
  // admin-модули (напр. журнал аудита — эндпоинт SYSTEM_ADMIN-only) не показываем прочим,
  // иначе COMPANY_ADMIN откроет плитку и получит 403.
  const modules = MODULES.filter(m => !m.admin || sysAdmin);

  const stBadge = (st: St) =>
    st === 'done' ? <span className="badge green">{t('set.st.done')}</span>
    : st === 'partial' ? <span className="badge amber">{t('set.st.partial')}</span>
    : st === 'info' ? <span className="badge blue">{t('set.st.info')}</span>
    : <span className="badge gray">{t('set.st.planned')}</span>;

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('nav.settings')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('set.lead')}</div>
        </div>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('set.note')}</div>

      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 14 }}>
        {modules.map(m => {
          const inner = (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
                <span className={`k-ic ${m.cls}`}><Icon d={m.icon} cls="" /></span>
                <div style={{ fontWeight: 700, fontSize: 14, flex: 1 }}>{t(`set.mod.${m.key}`)}</div>
                {m.href && <Icon d={P.chevron} cls="" style={{ width: 16, height: 16, color: 'var(--muted)' }} />}
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--muted)', minHeight: 34, marginBottom: 10 }}>{t(`set.moddesc.${m.key}`)}</div>
              {stBadge(m.st)}
            </>
          );
          const style: React.CSSProperties = { display: 'block', padding: 16, textDecoration: 'none', color: 'inherit' };
          return m.href
            ? <Link key={m.key} href={m.href} className="card clickable" style={style}>{inner}</Link>
            : <div key={m.key} className="card" style={{ ...style, opacity: 0.92 }}>{inner}</div>;
        })}
      </div>
    </>
  );
}
