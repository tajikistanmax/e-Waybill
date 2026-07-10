/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    return [
      { source: '/md-api/:path*', destination: 'http://localhost:8081/:path*' },
      { source: '/wb-api/:path*', destination: 'http://localhost:8082/:path*' },
    ];
  },
};

export default nextConfig;
