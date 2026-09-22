'use client';

import { useEffect, useMemo, useState } from 'react';
import { useT } from '@/lib/i18n';

/**
 * Постраничный вывод длинных таблиц (отчёты, журналы, справочники).
 * Хук возвращает срез текущей страницы и сам сбрасывает страницу при смене данных —
 * иначе после нового запроса пользователь оставался на несуществующей странице.
 */
export function usePaged<T>(items: T[], perPage = 20) {
  const [page, setPage] = useState(1);
  useEffect(() => { setPage(1); }, [items]);
  const pages = Math.max(1, Math.ceil(items.length / perPage));
  const safePage = Math.min(page, pages);
  const view = useMemo(() => items.slice((safePage - 1) * perPage, safePage * perPage), [items, safePage, perPage]);
  return { page: safePage, pages, view, total: items.length, setPage };
}

/** Подвал таблицы: всего записей + переключатель страниц (скрыт, когда страница одна). */
export function Pager({ page, pages, total, setPage, unitLabel }: {
  page: number; pages: number; total: number; setPage: (p: number) => void; unitLabel?: string;
}) {
  const { t } = useT();
  return (
    <div style={{ display: 'flex', alignItems: 'center', marginTop: 12, fontSize: 12.5, color: 'var(--muted)' }}>
      <span>{unitLabel ?? t('dash.total')}: <b style={{ color: 'var(--ink)' }}>{total.toLocaleString('ru-RU')}</b></span>
      <span style={{ flex: 1 }} />
      {pages > 1 && (
        <>
          <button type="button" className="btn secondary" disabled={page <= 1} onClick={() => setPage(Math.max(1, page - 1))} style={{ padding: '5px 11px' }}>‹</button>
          <span style={{ margin: '0 12px' }}>{page} / {pages}</span>
          <button type="button" className="btn secondary" disabled={page >= pages} onClick={() => setPage(Math.min(pages, page + 1))} style={{ padding: '5px 11px' }}>›</button>
        </>
      )}
    </div>
  );
}
