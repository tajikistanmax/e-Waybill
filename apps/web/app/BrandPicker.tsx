'use client';

import { useCallback, useEffect, useState } from 'react';
import { md, type BrandRef } from '@/lib/api';
import { useT } from '@/lib/i18n';
import { SearchSelect, type SSOption } from './SearchSelect';

// Справочник марок один на платформу и меняется редко — грузим один раз на вкладку.
let cache: Promise<BrandRef[]> | null = null;
const loadBrands = () => (cache ??= md.brands().catch(e => { cache = null; throw e; }));

/**
 * Марка ТС — выбор из справочника марок (legacy parkings.brand_id, сверка 25.09 F4): расчёт ищет нормы
 * расхода по имени марки, и опечатка свободного текста давала норму 0. Поиск по названию, модели и коду.
 * Значение — имя марки (как хранит карточка ТС); `onPick` отдаёт запись справочника целиком.
 */
export function BrandPicker({ value, onChange, onPick, disabled }: {
  value: string;
  onChange: (name: string) => void;
  onPick?: (b: BrandRef) => void;
  disabled?: boolean;
}) {
  const { t } = useT();
  const [brands, setBrands] = useState<BrandRef[]>([]);
  useEffect(() => { loadBrands().then(setBrands).catch(() => setBrands([])); }, []);

  const search = useCallback(async (q: string): Promise<SSOption[]> => {
    const ql = q.trim().toLowerCase();
    return brands
      .filter(b => !ql || b.name.toLowerCase().includes(ql) || String(b.model ?? '').toLowerCase().includes(ql)
        || String(b.number ?? '').includes(ql))
      .slice(0, 30)
      .map(b => ({ value: String(b.id), label: b.name,
        sub: [b.model, b.number ? `${t('brand.code')} ${b.number}` : ''].filter(Boolean).join(' · ') }));
  }, [brands, t]);

  const known = !value || brands.length === 0 || brands.some(b => b.name.toLowerCase() === value.trim().toLowerCase());
  return (
    <>
      <SearchSelect value={value} selectedLabel={value} disabled={disabled}
        placeholder={t('brand.search')} onSearch={search}
        onSelect={o => {
          const b = brands.find(x => String(x.id) === o.value);
          onChange(o.label);
          if (b) onPick?.(b);
        }}
        onClear={() => onChange('')}
        loadingText={t('cn.searching')} emptyText={t('cn.notfound')} hintText={t('brand.hint')} />
      {!known && <div style={{ fontSize: 11.5, color: 'var(--amber)', marginTop: 3 }}>{t('brand.notindict')}</div>}
    </>
  );
}
