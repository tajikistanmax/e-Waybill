import { LegacyQrRedirect } from '@epd/shared/ui/LegacyQrRedirect';

// Старые бумажные QR «Роҳхат» /qrcode/{тип}/{id} → проверка перенесённого листа (сверка 25.09, G5).
export default async function LegacyQrPage({ params }: { params: Promise<{ type: string; token: string }> }) {
  const { type, token } = await params;
  return <LegacyQrRedirect type={type} token={token} />;
}
