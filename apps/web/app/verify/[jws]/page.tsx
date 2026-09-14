import { VerifyView } from '@epd/shared/ui/VerifyView';

/**
 * Публичная проверка путевого листа по QR-коду. Рендер — общий компонент VerifyView
 * из @epd/shared (тот же, что в публичном контуре apps/public). В кабинете надзора
 * инспектор открывает эту же страницу; эндпоинт /wb-api/.../verify публичный.
 */
export default async function VerifyPage({ params }: { params: Promise<{ jws: string }> }) {
  const { jws } = await params;
  return <VerifyView jws={jws} />;
}
