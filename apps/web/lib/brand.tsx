'use client';

import { createContext, useContext, useEffect, useState, type CSSProperties } from 'react';
import { md, type PlatformSetting } from '@/lib/api';
import { Icon, P } from '@/app/icons';

/**
 * Бренд платформы (§29): название и подзаголовок — из публичных настроек (редактирует админ на
 * /settings/branding), с фолбэком на зашитые значения. Изображения (логотип/фон входа) —
 * по URL через <BrandLogo>/branding.url с фолбэком на зашитый глиф/картинку, если админ их не задал.
 * Провайдер оборачивает всё приложение (в т.ч. публичную страницу входа) — один запрос на сессию.
 */
type Brand = { name: string; subtitle: string; loaded: boolean };

const FALLBACK: Brand = { name: 'е-Роҳхат', subtitle: '', loaded: false };

const BrandContext = createContext<Brand>(FALLBACK);

export const useBrand = () => useContext(BrandContext);

export function BrandProvider({ children }: { children: React.ReactNode }) {
  const [brand, setBrand] = useState<Brand>(FALLBACK);
  useEffect(() => {
    md.publicSettings()
      .then((rows: PlatformSetting[]) => {
        const m = Object.fromEntries(rows
          .filter(r => r.category === 'branding')
          .map(r => [r.settingKey, (r.settingValue ?? '').trim()]));
        setBrand({
          name: m.brand_name || FALLBACK.name,
          subtitle: m.brand_subtitle || '',
          loaded: true,
        });
      })
      .catch(() => setBrand(b => ({ ...b, loaded: true })));
  }, []);
  return <BrandContext.Provider value={brand}>{children}</BrandContext.Provider>;
}

/**
 * Логотип: пользовательское изображение (branding/logo), при его отсутствии (404) —
 * зашитый глиф. `nonce` в URL позволяет форсировать перезагрузку после смены картинки.
 */
export function BrandLogo({ style, nonce }: { style?: CSSProperties; nonce?: number }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [nonce]);
  if (failed) return <Icon d={P.docActive} cls="" />;
  return (
    <img
      src={md.branding.url('logo') + (nonce ? `?v=${nonce}` : '')}
      alt=""
      onError={() => setFailed(true)}
      style={{ width: '100%', height: '100%', objectFit: 'contain', ...style }}
    />
  );
}
