'use client';

import { useEffect, useRef, useState } from 'react';

export type SSOption = { value: string; label: string; sub?: string };

/**
 * Автокомплит с серверным поиском: диспетчер/медик/механик вводят часть госномера
 * или ИНН/ФИО, а не листают тысячи записей. Дебаунс 250мс, выбор — карточка с крестиком.
 * onSearch/onSelect держим в ref — не пересоздаём эффект на каждый рендер.
 */
export function SearchSelect({
  value, selectedLabel, placeholder, disabled,
  onSearch, onSelect, onClear,
  loadingText = 'Поиск…', emptyText = 'Ничего не найдено', hintText,
}: {
  value: string;
  selectedLabel: string;
  placeholder: string;
  disabled?: boolean;
  onSearch: (q: string) => Promise<SSOption[]>;
  onSelect: (opt: SSOption) => void;
  onClear?: () => void;
  loadingText?: string;
  emptyText?: string;
  hintText?: string;
}) {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [opts, setOpts] = useState<SSOption[]>([]);
  const [loading, setLoading] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const timer = useRef<number | undefined>(undefined);
  const searchRef = useRef(onSearch);
  searchRef.current = onSearch;

  useEffect(() => {
    if (!open) return;
    window.clearTimeout(timer.current);
    let ignore = false;
    timer.current = window.setTimeout(() => {
      setLoading(true);
      searchRef.current(q)
        .then(r => { if (!ignore) setOpts(r); })
        .catch(() => { if (!ignore) setOpts([]); })
        .finally(() => { if (!ignore) setLoading(false); });
    }, 250);
    return () => { ignore = true; window.clearTimeout(timer.current); };
  }, [q, open]);

  useEffect(() => {
    const h = (e: MouseEvent) => { if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, []);

  // Выбранное значение — карточка-«чип» с возможностью сброса.
  if (value && !open) {
    return (
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '9px 12px',
        border: '1px solid var(--blue-200, #bfdbfe)', borderRadius: 8, background: 'var(--blue-050, #eff6ff)',
        color: 'var(--blue-700, #1d4ed8)', fontWeight: 600, fontSize: 14,
      }}>
        <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{selectedLabel}</span>
        {!disabled && (
          <button type="button" aria-label="Сбросить"
            onClick={() => { onClear?.(); setOpen(true); setQ(''); setOpts([]); }}
            style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--muted)', fontSize: 18, lineHeight: 1, padding: 0 }}>×</button>
        )}
      </div>
    );
  }

  return (
    <div ref={boxRef} style={{ position: 'relative' }}>
      <input
        type="text" value={q} disabled={disabled} placeholder={placeholder}
        autoComplete="off"
        onFocus={() => setOpen(true)}
        onChange={e => { setQ(e.target.value); setOpen(true); }}
      />
      {open && !disabled && (
        <div style={{
          position: 'absolute', top: 'calc(100% + 4px)', left: 0, right: 0, zIndex: 30,
          background: 'var(--card, #fff)', border: '1px solid var(--border, #e5e7eb)', borderRadius: 8,
          boxShadow: '0 8px 24px rgba(15,23,42,0.12)', maxHeight: 280, overflowY: 'auto',
        }}>
          {loading && <div style={{ padding: '10px 12px', color: 'var(--muted)', fontSize: 13 }}>{loadingText}</div>}
          {!loading && opts.length === 0 && (
            <div style={{ padding: '10px 12px', color: 'var(--muted)', fontSize: 13 }}>{q.trim() ? emptyText : (hintText ?? emptyText)}</div>
          )}
          {!loading && opts.map(o => (
            <div key={o.value} onClick={() => { onSelect(o); setOpen(false); setQ(''); setOpts([]); }}
              style={{ padding: '9px 12px', cursor: 'pointer', borderTop: '1px solid var(--border, #f1f5f9)' }}
              onMouseDown={e => e.preventDefault()}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--blue-050, #eff6ff)')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
              <div style={{ fontWeight: 600, fontSize: 14 }}>{o.label}</div>
              {o.sub && <div style={{ fontSize: 12, color: 'var(--muted)' }}>{o.sub}</div>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
