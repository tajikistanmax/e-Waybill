'use client';

import { useEffect, useMemo, useState } from 'react';
import { md } from '@/lib/api';
import { useT } from '@/lib/i18n';
import OrgDocuments from '../../company/OrgDocuments';

/**
 * Документы своей организации для перевозчика (сверка 25.09, F9): печать предприятия (SEAL) печатается
 * в графе «Ҷои муҳри корхона» бланков, но загрузить её было можно только из раздела «Компания», который
 * администратору перевозчика недоступен. Здесь — учредительные документы и печать своей организации
 * (у компании с филиалами — выбор организации). Одобряет загруженное Минтранс.
 */
export default function FleetOrganizationPage() {
  const { t } = useT();
  const [orgs, setOrgs] = useState<Record<string, unknown>[]>([]);
  const [rma, setRma] = useState('');
  useEffect(() => { md.organizations().then(setOrgs).catch(() => setOrgs([])); }, []);
  const head = useMemo(() => orgs.find(o => !o.parentRma || !orgs.some(x => x.rma === o.parentRma)) ?? orgs[0], [orgs]);
  useEffect(() => { if (!rma && head) setRma(String(head.rma)); }, [head, rma]);

  return (
    <div className="card">
      <h2 style={{ marginTop: 0 }}>{t('fleet.org.h')}</h2>
      <p style={{ color: 'var(--muted)', fontSize: 13, marginTop: -4 }}>{t('fleet.org.lead')}</p>
      {orgs.length > 1 && (
        <div style={{ maxWidth: 460, marginBottom: 12 }}>
          <label>{t('access.f.org')}</label>
          <select aria-label={t('access.f.org')} value={rma} onChange={e => setRma(e.target.value)}>
            {orgs.map(o => <option key={String(o.rma)} value={String(o.rma)}>{String(o.name)}{o.parentRma ? t('access.f.branch.suffix') : ''}</option>)}
          </select>
        </div>
      )}
      {rma && <OrgDocuments key={rma} rma={rma} />}
    </div>
  );
}
