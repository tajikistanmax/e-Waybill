// Единая точка формирования ссылки на публичную проверку ПЛ по QR.
//
// QR-код на бланке/в кабинете водителя/на печатной форме кодирует полный URL страницы
// проверки, чтобы камера телефона инспектора открывала её напрямую. После выделения
// публичного контура в отдельное приложение (apps/public) эта ссылка должна вести на его
// origin, а не на origin кабинета. Адрес задаётся сборочной переменной
// NEXT_PUBLIC_VERIFY_BASE_URL (напр. https://verify.epd.tj); в dev, если она не задана,
// падаем на текущий origin (монолит сам обслуживает /verify) — поведение не меняется.
export function verifyBaseUrl(): string {
  const configured = process.env.NEXT_PUBLIC_VERIFY_BASE_URL;
  if (configured) return configured.replace(/\/+$/, '');
  return typeof window !== 'undefined' ? window.location.origin : '';
}

/** Полная ссылка на страницу публичной проверки для подписи jws. */
export function verifyLink(jws: string): string {
  return `${verifyBaseUrl()}/verify/${jws}`;
}
