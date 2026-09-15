'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { md, setOrgScope } from './api';
import { useAuth } from './auth';

/**
 * Переключатель филиала для администратора компании: компания видит свои филиалы
 * и может сузить всю выборку до одного (заголовок X-Org-Scope, применяет бэкенд).
 * Для пользователей без филиалов провайдер прозрачен.
 */

export type OrgBranch = { rma: string; name: string; isCompany: boolean };

type Ctx = {
  branches: OrgBranch[];
  selected: string;             // '' = все филиалы
  setSelected: (rma: string) => void;
  hasBranches: boolean;
};

const OrgScopeContext = createContext<Ctx>({
  branches: [], selected: '', setSelected: () => {}, hasBranches: false,
});

export const useOrgScope = () => useContext(OrgScopeContext);

const LS_KEY = 'epd.orgScope';

export function OrgScopeProvider({ children }: { children: React.ReactNode }) {
  const { ready, authenticated, roles } = useAuth();
  const [branches, setBranches] = useState<OrgBranch[]>([]);
  const [selected, setSelectedState] = useState('');

  // Только COMPANY_ADMIN — переключатель сужает выборку до одного филиала СВОЕЙ компании
  // (X-Org-Scope, см. TenantScope.rmas()). SYSTEM_ADMIN раньше тоже сюда попадал: для него
  // myOrganizations() возвращает вообще все организации платформы (не «филиалы»), а
  // TenantScope.rmas() для платформенных ролей отдаёт пустую область ДО чтения заголовка —
  // выбор в дропдауне ничего не фильтрует на бэкенде, только зря перезагружает страницу
  // (найдено 2026-09-04 по вопросу пользователя «зачем оно там нужно»).
  const canSwitch = roles.includes('COMPANY_ADMIN');

  useEffect(() => {
    // применяем сохранённый выбор до первых запросов
    try {
      const saved = localStorage.getItem(LS_KEY) || '';
      if (saved) { setOrgScope(saved); setSelectedState(saved); }
    } catch { /* приватный режим */ }
  }, []);

  useEffect(() => {
    if (!ready || !authenticated || !canSwitch) { setBranches([]); return; }
    let alive = true;
    md.myOrganizations()
      .then(list => {
        if (!alive) return;
        const mapped: OrgBranch[] = list.map(o => ({
          rma: String(o.rma),
          name: String(o.name ?? o.rma),
          isCompany: !o.parentRma,
        }));
        // показываем переключатель только если это компания с филиалами
        setBranches(mapped.length > 1 ? mapped : []);
        // выбранного филиала больше нет в списке → сбрасываем
        setSelectedState(prev => {
          if (prev && !mapped.some(m => m.rma === prev)) {
            setOrgScope('');
            try { localStorage.removeItem(LS_KEY); } catch { /* ignore */ }
            return '';
          }
          return prev;
        });
      })
      .catch(() => { if (alive) setBranches([]); });
    return () => { alive = false; };
  }, [ready, authenticated, canSwitch]);

  const setSelected = useCallback((rma: string) => {
    setOrgScope(rma);
    setSelectedState(rma);
    try {
      if (rma) localStorage.setItem(LS_KEY, rma);
      else localStorage.removeItem(LS_KEY);
    } catch { /* ignore */ }
    // перезагрузка, чтобы все загруженные списки пересобрались под новой областью
    if (typeof window !== 'undefined') window.location.reload();
  }, []);

  const value = useMemo<Ctx>(() => ({
    branches,
    selected,
    setSelected,
    hasBranches: branches.length > 1,
  }), [branches, selected, setSelected]);

  return <OrgScopeContext.Provider value={value}>{children}</OrgScopeContext.Provider>;
}
