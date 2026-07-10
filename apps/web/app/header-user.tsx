'use client';

import { useAuth } from '@/lib/auth';

export function HeaderUser() {
  const { username, roles, logout } = useAuth();
  if (!username) return null;
  const roleLabel = roles.includes('SYSTEM_ADMIN') ? 'Администратор'
    : roles.includes('DISPATCHER') ? 'Диспетчер'
    : roles.includes('DOCTOR') ? 'Врач'
    : roles.includes('MECHANIC') ? 'Механик'
    : roles.includes('ACCOUNTANT') ? 'Бухгалтер'
    : roles.includes('COMPANY_ADMIN') ? 'Админ компании'
    : roles.includes('INSPECTOR') ? 'Инспектор' : '';
  return (
    <span className="header-user">
      <span className="who">{username}{roleLabel ? ` · ${roleLabel}` : ''}</span>
      <button onClick={logout}>Выход</button>
    </span>
  );
}
