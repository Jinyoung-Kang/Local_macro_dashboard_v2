"use client";

import { useState } from "react";
import { LineSeries, MultiLineSeries } from "@/components/charts";
import {
  Banner,
  Card,
  ErrorState,
  Freshness,
  Loading,
  Metric,
  Select,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { EMPTY, formatNumber, formatSigned } from "@/lib/format";
import type { LiquidityResponse } from "@/lib/types";

const PERIODS = [
  { value: "1", label: "최근 1년" },
  { value: "2", label: "최근 2년" },
  { value: "3", label: "최근 3년" },
  { value: "5", label: "최근 5년" },
  { value: "10", label: "최근 10년" },
];

/**
 * 🏢 연준 순유동성 트래커.
 *
 * 순유동성 = WALCL(연준 총자산) − TGA(재무부 일반계정) − ON RRP(역레포)
 * 시장에 실제로 남아 있는 달러 유동성의 근사치로, 위험자산 방향과 상관이
 * 높다고 알려진 지표입니다.
 */
export default function LiquidityPage() {
  const [years, setYears] = useState("3");
  const { data, loading, error, reload } = useApi<LiquidityResponse>(
    `/api/liquidity?years=${years}`,
    300_000,
  );

  if (loading && !data) {
    return <Loading label="순유동성 데이터를 불러오는 중…" />;
  }
  if (error) {
    return <ErrorState message={error} onRetry={reload} />;
  }

  const rows = data?.rows ?? [];
  const latest = data?.latest;

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-bright">🏢 연준 순유동성 트래커</h1>
          <p className="mt-1 text-xs text-muted">
            WALCL − TGA − ON RRP · 출처: FRED 공식 시계열 (주간 갱신)
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Select label="조회 기간" value={years} onChange={setYears} options={PERIODS} />
          <Freshness collectedAt={data?.collectedAtKst} stale={data?.stale} />
        </div>
      </header>

      {!data?.available && (
        <Banner tone="warn">
          {data?.message ?? "순유동성 데이터가 없습니다. 수집기를 실행하세요."}
        </Banner>
      )}

      {data?.isEstimated && (
        <Banner tone="danger">
          ⚠️ 추정치 모드입니다. FRED 확정치가 아니므로 수치를 그대로 신뢰하지 마세요.
        </Banner>
      )}

      {latest && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <Metric
            label="순유동성 (Net Liquidity)"
            value={
              latest.netLiquidityT === null
                ? EMPTY
                : `${formatNumber(latest.netLiquidityT, 3)} 조 달러`
            }
            delta={latest.deltaT}
            deltaText={
              latest.deltaT === null ? EMPTY : `${formatSigned(latest.deltaT, 3)} 조 달러`
            }
            caption={latest.date ? `기준일 ${latest.date}` : undefined}
          />
          <Metric
            label="연준 총자산 (WALCL)"
            value={
              latest.walclT === null || latest.walclT === undefined
                ? EMPTY
                : `${formatNumber(latest.walclT, 3)} 조 달러`
            }
          />
          <Metric
            label="재무부 일반계정 (TGA)"
            value={
              latest.tgaB === null || latest.tgaB === undefined
                ? EMPTY
                : `${formatNumber(latest.tgaB, 1)} 십억 달러`
            }
            caption="TGA 증가 = 시장에서 자금 흡수"
          />
          <Metric
            label="역레포 (ON RRP)"
            value={
              latest.rrpB === null || latest.rrpB === undefined
                ? EMPTY
                : `${formatNumber(latest.rrpB, 1)} 십억 달러`
            }
            caption="RRP 감소 = 시장으로 유동성 환류"
          />
        </div>
      )}

      {data?.momentum && (
        <div className="grid gap-3 sm:grid-cols-2">
          <Metric
            label="4주 변화"
            value={
              data.momentum.change4w === null
                ? EMPTY
                : `${formatSigned(data.momentum.change4w, 3)} 조 달러`
            }
            delta={data.momentum.change4w}
            deltaText=""
            caption="유동성은 방향과 속도가 함께 중요합니다."
          />
          <Metric
            label="12주 변화"
            value={
              data.momentum.change12w === null
                ? EMPTY
                : `${formatSigned(data.momentum.change12w, 3)} 조 달러`
            }
            delta={data.momentum.change12w}
            deltaText=""
          />
        </div>
      )}

      <Card title="📈 순유동성 추이" subtitle="단위: 조 달러">
        <LineSeries
          data={rows.map((row) => ({ date: row.date, value: row.netLiquidityT }))}
          unit="T"
          color="#3FB950"
          height={300}
        />
      </Card>

      <Card
        title="🧩 구성 항목 분해"
        subtitle="총자산이 늘어도 TGA·RRP가 더 늘면 시장 유동성은 줄어듭니다."
      >
        <MultiLineSeries
          data={rows.map((row) => ({
            date: row.date,
            WALCL: row.walclT,
            TGA: row.wtregenB / 1000,
            RRP: row.rrpB / 1000,
          }))}
          series={[
            { key: "WALCL", name: "연준 총자산 (조$)", color: "#58A6FF" },
            { key: "TGA", name: "재무부 계정 (조$)", color: "#D29922" },
            { key: "RRP", name: "역레포 (조$)", color: "#F85149" },
          ]}
          unit="T"
          height={300}
        />
      </Card>
    </div>
  );
}
