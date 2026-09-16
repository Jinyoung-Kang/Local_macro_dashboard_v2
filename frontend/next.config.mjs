/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // 백엔드 주소는 런타임 환경변수로 주입합니다(컨테이너 이미지 재빌드 없이 변경 가능).
  env: {
    NEXT_PUBLIC_API_BASE: process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080",
  },
};

export default nextConfig;
