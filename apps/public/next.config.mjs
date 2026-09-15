/** @type {import('next').NextConfig} */
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Корень монорепо (см. подробный коммент в apps/web/next.config.mjs): детерминирует layout
// standalone-вывода — .next/standalone/apps/public/server.js. @epd/shared через transpilePackages
// вкомпилирован в чанки приложения, отдельным пакетом в standalone не попадает.
const repoRoot = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const nextConfig = {
  // standalone-сборка для контейнера.
  output: 'standalone',
  outputFileTracingRoot: repoRoot,
  // Общий пакет монорепо компилируется как исходники (TSX).
  transpilePackages: ['@epd/shared'],
  // На Windows форк воркеров статической генерации периодически падает — cpus=1 стабилизирует
  // (безопасно и в Linux-контейнере). Тот же приём, что в apps/web.
  experimental: {
    cpus: 1,
    workerThreads: false,
  },
  async rewrites() {
    // Публичный (интернет, аноним) контур проксирует ТОЛЬКО публичный verify-эндпоинт, а НЕ весь
    // waybill-service: широкий `/wb-api/:path*` мостил бы наружу webhook оплаты, actuator и пр.
    // (security-аудит M1). VerifyView обращается единственно к /wb-api/api/v1/verify/{jws}.
    // 127.0.0.1 (не localhost): на Windows undici иначе резолвит в IPv6 ::1 и запрос зависает.
    const wbApi = process.env.WB_API_URL || 'http://127.0.0.1:8082';
    return [{ source: '/wb-api/api/v1/verify/:path*', destination: `${wbApi}/api/v1/verify/:path*` }];
  },
};

export default nextConfig;
