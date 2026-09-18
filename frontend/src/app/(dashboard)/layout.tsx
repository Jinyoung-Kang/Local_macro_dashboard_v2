"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { MarketClock } from "@/components/MarketClock";
import { MENUS, Sidebar } from "@/components/Sidebar";
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

/**
 * 브라우저 탭 제목을 현재 메뉴 이름으로 바꿉니다.
 *
 * <p>모든 화면이 클라이언트 컴포넌트라 Next의 정적 metadata를 페이지마다 둘 수
 * 없습니다. 그대로 두면 탭 12개가 전부 같은 제목이라, 매크로·레이더·13F를 함께
 * 띄워 두고 비교할 때 어느 탭이 무엇인지 구분할 수 없습니다.
 */
function useDocumentTitle(pathname: string) {
  useEffect(() => {
    const menu = MENUS.find(
      (entry) => pathname === entry.href || pathname.startsWith(`${entry.href}/`),
    );
    // 이모지는 탭에서 잘리기 쉬워 떼고, 뒤에 앱 이름을 붙입니다.
    // 🏛️처럼 이모지 뒤에 이형 선택자(U+FE0F)가 붙는 글자가 있어, 그림 문자만
    // 지우면 보이지 않는 문자가 제목 앞에 남습니다. 함께 지웁니다.
    const name = menu?.label
      .replace(/^[\p{Extended_Pictographic}\u200d\ufe0f\s]+/u, "")
      .trim();
    document.title = name ? `${name} · 매크로 대시보드` : "매크로 대시보드";
  }, [pathname]);
}

function DashboardShell({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  const router = useRouter();
  const pathname = usePathname();
  useDocumentTitle(pathname);
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
