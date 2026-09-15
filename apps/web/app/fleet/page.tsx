'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';

/**
 * /fleet → первый доступный роли раздел. Механику — «Транспорт», врачу — «Водители»,
 * управляющим ролям — «Транспорт». У каждого раздела свой адрес.
 */
export default function FleetIndex() {
  const router = useRouter();
  const { ready, roles } = useAuth();

  useEffect(() => {
    if (!ready) return;
    const canManage = ['DISPATCHER', 'COMPANY_ADMIN', 'BRANCH_ADMIN', 'SYSTEM_ADMIN'].some(r => roles.includes(r));
    const target = canManage || roles.includes('MECHANIC') ? '/fleet/vehicles'
      : roles.includes('DOCTOR') ? '/fleet/drivers'
        : '/fleet/vehicles';
    router.replace(target);
  }, [ready, roles, router]);

  return null;
}
