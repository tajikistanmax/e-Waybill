import { VerifyView } from '@epd/shared/ui/VerifyView';

/**
 * Публичная (анонимная) проверка ПЛ по QR-коду. Рендер — общий компонент VerifyView
 * из @epd/shared (тот же, что в кабинете надзора). Эндпоинт /wb-api/.../verify публичный.
 */
export default async function VerifyPage({ params }: { params: Promise<{ jws: string }> }) {
  const { jws } = await params;
  return <VerifyView jws={jws} />;
}
