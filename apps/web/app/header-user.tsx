'use client';

import { useAuth } from '@/lib/auth';

export function HeaderUser() {
  const { username, roles, logout } = useAuth();
  if (!username) return null;
  const roleLabel = roles.includes('SYSTEM_ADMIN') ? 'Администратор'
    : roles.includes('DISPATCHER') ? 'Диспетчер'
    : roles.includes('DOCTOR') ? 'Врач'
    : roles.includes('MECHANIC') ? 'Механик'
    : roles.includes('COMPANY_ADMIN') ? 'Админ компании'
    : roles.includes('INSPECTOR') ? 'Инспектор' : '';
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}>
      <span style={{ opacity: .9 }}>{username}{roleLabel ? ` · ${roleLabel}` : ''}</span>
      <button onClick={logout} style={{
        background: 'rgba(255,255,255,.15)', color: '#fff', border: '1px solid rgba(255,255,255,.4)',
        borderRadius: 6, padding: '4px 10px', cursor: 'pointer', fontSize: 12,
      }}>Выход</button>
    </span>
  );
}
