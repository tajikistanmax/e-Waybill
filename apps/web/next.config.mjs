/** @type {import('next').NextConfig} */
const nextConfig = {
  // standalone-сборка для контейнера: .next/standalone с минимальным сервером и зависимостями
  output: 'standalone',
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
