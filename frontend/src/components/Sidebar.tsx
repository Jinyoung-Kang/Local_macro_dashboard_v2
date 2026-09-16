"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { apiPost, auth } from "@/lib/api";
import { Button } from "@/components/ui";

/**
 * 좌측 메뉴 — 구버전 사이드바의 12개 메뉴를 그대로 옮겼습니다.
 * 순서도 같습니다: 분석 메뉴 → 데이터 상태 → AI → 연결 진단.
 */
export const MENUS = [
  { href: "/macro", label: "📊 거시경제 매크로 지표" },
  { href: "/liquidity", label: "🏢 연준 순유동성 트래커" },
  { href: "/sector", label: "🔄 섹터 & 자산군 로테이션" },
  { href: "/institutions", label: "📑 기관 13F 포트폴리오 분석" },
  { href: "/consensus", label: "🎯 기관 13F Money 교집합" },
  { href: "/cot", label: "🏛️ 글로벌 투기세력 (COT)" },
  { href: "/krx", label: "🇰🇷 국내 파생 & 투기세력 (KRX)" },
  { href: "/radar", label: "📡 외국인/기관 수급 레이더" },
  { href: "/status", label: "🗄️ 데이터 저장소 상태" },
  { href: "/ai/report", label: "🤖 AI 종합 데이터 분석" },
  { href: "/ai/test", label: "🤖 AI API 연결 테스트" },
  { href: "/toss", label: "🔌 토스증권 API 테스트" },
];

export function Sidebar({ readMode }: { readMode?: string }) {
  const pathname = usePathname();
  const router = useRouter();
  const [refreshing, setRefreshing] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = async () => {
    setRefreshing(true);
    setMessage(null);
    try {
      const result = await apiPost<{ message?: string }>("/api/status/refresh?runFast=true");
      setMessage(result.message ?? "새로고침을 요청했습니다.");
      router.refresh();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "새로고침에 실패했습니다.");
    } finally {
      setRefreshing(false);
    }
  };

  const logout = async () => {
    await auth.logout();
    router.push("/login");
  };

  return (
    <aside className="flex w-full shrink-0 flex-col gap-4 border-b border-border bg-surface p-4 lg:h-screen lg:w-72 lg:border-b-0 lg:border-r lg:overflow-y-auto">
      <div>
        <h1 className="text-sm font-bold text-bright">대시보드 메뉴</h1>
        <p className="mt-1 text-[11px] leading-relaxed text-muted">
          글로벌 매크로 및 시장 수급 정밀 분석 시스템
        </p>
      </div>

      <nav className="flex flex-col gap-1">
        {MENUS.map((menu) => {
          const active = pathname === menu.href || pathname.startsWith(`${menu.href}/`);
          return (
            <Link
              key={menu.href}
              href={menu.href}
              className={`rounded-lg border px-3 py-2 text-xs transition ${
                active
                  ? "border-accent/50 bg-accent/10 text-accent"
                  : "border-white/5 bg-white/[0.02] text-body hover:translate-x-1 hover:border-accent/30 hover:bg-accent/10"
              }`}
            >
              {menu.label}
            </Link>
          );
        })}
      </nav>

      <div className="mt-auto flex flex-col gap-2 border-t border-border pt-4">
        <div className="text-[11px] text-muted">
          읽기 모드:{" "}
          <span className="font-semibold text-body">{readMode ?? "auto"}</span>
          {readMode === "store_only" && (
            <p className="mt-1 leading-relaxed">
              저장본만 사용합니다. 화면이 외부 수집을 기다리지 않습니다.
            </p>
          )}
        </div>

        <Button variant="primary" onClick={refresh} disabled={refreshing}>
          {refreshing ? "요청 중…" : "데이터 수동 새로고침 🚀"}
        </Button>
        {message && <p className="text-[11px] leading-relaxed text-muted">{message}</p>}

        <Button onClick={logout}>로그아웃</Button>
        <p className="text-[11px] text-muted">© 2026 Local Macro Dashboard v2</p>
      </div>
    </aside>
  );
}
