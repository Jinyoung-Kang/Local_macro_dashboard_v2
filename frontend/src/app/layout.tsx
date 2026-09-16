import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Global Macro & 13F Dashboard",
  description:
    "글로벌 매크로 지표·연준 순유동성·섹터 로테이션·CFTC COT·KRX 파생·SEC 13F·국내 수급 레이더를 한 화면에서 보는 대시보드",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body className="min-h-screen bg-canvas text-body antialiased">{children}</body>
    </html>
  );
}
