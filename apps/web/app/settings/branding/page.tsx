'use client';

import Link from 'next/link';
import { useRef, useState } from 'react';
import { useT } from '@/lib/i18n';
import { useAuth } from '@/lib/auth';
import { Icon, P } from '../../icons';
import { SettingsEditor } from '../SettingsEditor';
import { md } from '@/lib/api';

/**
 * Брендинг (§29): администратор задаёт название/подзаголовок платформы (настройки категории
 * branding) и загружает изображения — логотип и фон страницы входа. Изображения хранятся в БД
 * и отдаются публично; при отсутствии используется зашитый дефолт.
 */
export default function BrandingSettingsPage() {
  const { t } = useT();
  const { roles } = useAuth();
  const canEdit = roles.includes('SYSTEM_ADMIN');

  return (
    <>
      <div className="toolbar">
        <div>
          <h1>{t('brand.page.h')}</h1>
          <div className="page-lead" style={{ margin: 0 }}>{t('brand.page.lead')}</div>
        </div>
        <Link href="/settings" className="btn secondary" style={{ marginLeft: 'auto', textDecoration: 'none' }}>
          <Icon d={P.chevron} cls="" style={{ width: 15, height: 15, transform: 'rotate(180deg)' }} /> {t('nav.settings')}
        </Link>
      </div>

      <div className="hint" style={{ marginBottom: 18 }}>{t('brand.page.note')}</div>

      <h2 style={{ margin: '4px 0 6px' }}>{t('brand.texts.h')}</h2>
      <div className="page-lead" style={{ marginTop: 0, marginBottom: 14 }}>{t('brand.texts.lead')}</div>
      <SettingsEditor category="branding" />

      <h2 style={{ margin: '28px 0 6px' }}>{t('brand.images.h')}</h2>
      <div className="page-lead" style={{ marginTop: 0, marginBottom: 14 }}>{t('brand.images.lead')}</div>
      <div className="kpi-row" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: 14 }}>
        <ImageUpload assetKey="logo" title={t('brand.logo.t')} hint={t('brand.logo.d')} preview="logo" canEdit={canEdit} />
        <ImageUpload assetKey="login_bg" title={t('brand.bg.t')} hint={t('brand.bg.d')} preview="wide" canEdit={canEdit} />
      </div>
    </>
  );
}

function ImageUpload({ assetKey, title, hint, preview, canEdit }: {
  assetKey: 'logo' | 'login_bg';
  title: string;
  hint: string;
  preview: 'logo' | 'wide';
  canEdit: boolean;
}) {
  const { t } = useT();
  const [nonce, setNonce] = useState(1);
  const [missing, setMissing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  async function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setBusy(true); setMsg(null);
    try {
      await md.branding.upload(assetKey, file);
      setMissing(false);
      setNonce(n => n + 1);
      setMsg({ ok: true, text: t('brand.saved') });
    } catch (err) {
      setMsg({ ok: false, text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  }

  async function onReset() {
    setBusy(true); setMsg(null);
    try {
      await md.branding.reset(assetKey);
      setMissing(true);
      setNonce(n => n + 1);
      setMsg({ ok: true, text: t('brand.reset.done') });
    } catch (err) {
      setMsg({ ok: false, text: err instanceof Error ? err.message : String(err) });
    } finally {
      setBusy(false);
    }
  }

  const box: React.CSSProperties = preview === 'logo'
    ? { width: 72, height: 72, borderRadius: 12 }
    : { width: '100%', height: 120, borderRadius: 10 };

  return (
    <div className="card" style={{ padding: 16 }}>
      <div style={{ fontWeight: 700, fontSize: 14, marginBottom: 4 }}>{title}</div>
      <div style={{ fontSize: 12.5, color: 'var(--muted)', marginBottom: 12 }}>{hint}</div>

      <div style={{ ...box, border: '1px dashed var(--line)', background: 'var(--bg-soft, #f6f8fb)', display: 'grid', placeItems: 'center', overflow: 'hidden', marginBottom: 12 }}>
        {missing ? (
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>{t('brand.default')}</span>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={md.branding.url(assetKey) + `?v=${nonce}`}
            alt={title}
            onError={() => setMissing(true)}
            style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }}
          />
        )}
      </div>

      {canEdit ? (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" onChange={onFile} disabled={busy} style={{ display: 'none' }} id={`f-${assetKey}`} />
          <label htmlFor={`f-${assetKey}`} className="btn" style={{ cursor: busy ? 'default' : 'pointer', opacity: busy ? 0.6 : 1 }}>
            <Icon d={P.plus} cls="" style={{ width: 15, height: 15 }} /> {t('brand.choose')}
          </label>
          <button className="btn secondary" onClick={onReset} disabled={busy}>{t('brand.reset')}</button>
        </div>
      ) : (
        <div className="hint">{t('set.readonly')}</div>
      )}

      {msg && (
        <div style={{ marginTop: 10, fontSize: 12.5, color: msg.ok ? 'var(--green)' : 'var(--red)' }}>{msg.text}</div>
      )}
    </div>
  );
}
