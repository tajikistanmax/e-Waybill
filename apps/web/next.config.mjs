/** @type {import('next').NextConfig} */
const nextConfig = {
  // standalone-сборка для контейнера: .next/standalone с минимальным сервером и зависимостями
  output: 'standalone',
  async rewrites() {
    // Адреса backend-сервисов конфигурируемы через env (dev-дефолты — localhost).
    // ВАЖНО: Next вычисляет rewrites на этапе сборки, поэтому в контейнере
    // MD_API_URL/WB_API_URL передаются также как build-arg (см. apps/web/Dockerfile).
    const mdApi = process.env.MD_API_URL || 'http://localhost:8081';
    const wbApi = process.env.WB_API_URL || 'http://localhost:8082';
    return [
      { source: '/md-api/:path*', destination: `${mdApi}/:path*` },
      { source: '/wb-api/:path*', destination: `${wbApi}/:path*` },
    ];
  },
};

export default nextConfig;
