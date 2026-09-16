"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { MarketClock } from "@/components/MarketClock";
import { Sidebar } from "@/components/Sidebar";
import { useApi } from "@/hooks/useApi";
import { RefreshProvider } from "@/hooks/useRefreshSignal";

/**
 * 대시보드 공통 레이아웃 — 사이드바 + 거래소 시계.
 *
 * 세션이 없으면 로그인 화면으로 보냅니다. 구버전의 "비밀번호 잠금"과 같은
 * 역할이며, 실제 접근 차단은 백엔드가 합니다(프런트 검사는 UX용입니다).
 */
export default function DashboardLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  // 레이아웃 자신도 useApi를 쓰므로 Provider 안쪽에 있어야 합니다.
  // (수동 새로고침이 사이드바의 읽기 모드 표시까지 갱신합니다.)
  return (
    <RefreshProvider>
      <DashboardShell>{children}</DashboardShell>
    </RefreshProvider>
  );
}

function DashboardShell({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  const router = useRouter();
  const { data, loading, unauthorized } = useApi<{ authenticated: boolean }>(
    "/api/auth/session",
  );

  const authenticated = data?.authenticated ?? false;

  useEffect(() => {
    if (!loading && (unauthorized || (data && !authenticated))) {
      router.replace("/login");
    }
  }, [loading, unauthorized, data, authenticated, router]);

  const status = useApi<{ readMode?: string }>(authenticated ? "/api/status" : null);

  if (loading || !authenticated) {
    return (
      <main className="flex min-h-screen items-center justify-center text-sm text-muted">
        세션을 확인하는 중…
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      <Sidebar readMode={status.data?.readMode} />
      <main className="flex-1 overflow-x-hidden p-4 sm:p-6 lg:h-screen lg:overflow-y-auto">
        <div className="mb-5">
          <MarketClock />
        </div>
        {children}
      </main>
    </div>
  );
}
