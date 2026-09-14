/** @type {import('next').NextConfig} */
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Корень монорепо (на два уровня выше apps/web). Задаём явно, чтобы layout standalone-вывода
// был детерминирован и не зависел от авто-определения (иначе Next печатает предупреждение о
// «выведенном» корне workspace): .next(-profile)/standalone/apps/web/server.js (см. Dockerfile).
// Сам @epd/shared в standalone отдельным пакетом НЕ попадает — он через transpilePackages
// вкомпилирован в чанки приложения (.next), поэтому исходники packages/shared в рантайме не нужны.
const repoRoot = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

// Профильная сборка (NEXT_PUBLIC_APP_PROFILE = waybill | oversight): каждый профиль пишет в свой
// distDir, чтобы waybill и oversight можно было собирать/запускать параллельно из одного кода
// (иначе общий .next конфликтует). Без профиля (монолит) — обычный .next.
const profile = process.env.NEXT_PUBLIC_APP_PROFILE;
const nextConfig = {
  distDir: profile ? `.next-${profile}` : '.next',
  // standalone-сборка для контейнера: .next/standalone с минимальным сервером и зависимостями
  output: 'standalone',
  outputFileTracingRoot: repoRoot,
  // Общий пакет монорепо компилируется как исходники (TSX) — Next должен его транспилировать.
  transpilePackages: ['@epd/shared'],
  // На Windows форк воркеров статической генерации периодически падает нативно
  // (spawn UNKNOWN / worker exited): антивирус/ресурсы перехватывают создание
  // дочерних процессов. cpus=1 сводит фазу генерации к одному воркеру и делает
  // сборку стабильной. В Linux-контейнере значения безопасны.
  experimental: {
    cpus: 1,
    workerThreads: false,
  },
  async rewrites() {
    // Адреса backend-сервисов конфигурируемы через env (dev-дефолты — localhost).
    // ВАЖНО: Next вычисляет rewrites на этапе сборки, поэтому в контейнере
    // MD_API_URL/WB_API_URL передаются также как build-arg (см. apps/web/Dockerfile).
    // 127.0.0.1 (не localhost): на Windows Node/undici резолвит localhost в IPv6 ::1
    // через happy-eyeballs, из-за чего прокси-запросы к backend периодически зависают.
    const mdApi = process.env.MD_API_URL || 'http://127.0.0.1:8081';
    const wbApi = process.env.WB_API_URL || 'http://127.0.0.1:8082';
    return [
      { source: '/md-api/:path*', destination: `${mdApi}/:path*` },
      { source: '/wb-api/:path*', destination: `${wbApi}/:path*` },
    ];
  },
};

export default nextConfig;
