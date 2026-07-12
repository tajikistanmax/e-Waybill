'use client';

import { useEffect, useState } from 'react';
import { md } from '@/lib/api';
import { useT } from '@/lib/i18n';

/**
 * Баннер режима технических работ (§29). Читает публичную настройку general/maintenance_mode
 * (без токена — виден и на странице входа). Только предупреждение; доступ не блокирует.
 * Администратор включает на «Настройки → Общие».
 */
export function MaintenanceBanner() {
  const { t } = useT();
  const [on, setOn] = useState(false);

  useEffect(() => {
    md.publicSettings()
      .then(list => setOn(list.some(s => s.settingKey === 'maintenance_mode' && s.settingValue === 'true')))
      .catch(() => setOn(false));
  }, []);

  if (!on) return null;
  return (
    <div style={{
      background: '#b45309', color: '#fff', padding: '9px 16px', fontSize: 13, fontWeight: 600,
      textAlign: 'center', display: 'flex', gap: 8, justifyContent: 'center', alignItems: 'center',
    }}>
      <span aria-hidden>⚠</span> {t('maint.banner')}
    </div>
  );
}
