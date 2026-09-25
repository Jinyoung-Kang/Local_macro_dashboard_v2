"use client";

import { LineSeries, SERIES_COLORS, SignedBars } from "@/components/charts";
import { Banner, Card, ErrorState, Loading, Metric, Table } from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { deltaColor, EMPTY, formatKrw, formatNumber, formatPercent } from "@/lib/format";
import type { SeoulHousingResponse } from "@/lib/types";

/**
 * 🏠 서울 아파트 실거래 — 거래량과 평당가 중위값 (국토교통부 실거래가).
 *
 * 거래량은 가격보다 먼저 움직이는 경향이 있어 금리·유동성과 함께 보는 선행 신호입니다.
 * 통계 계산은 백엔드(analytics/HousingStats)가 하고, 화면은 그대로 그립니다.
 *
 * 최근 두 달은 신고 기한(계약 후 30일) 때문에 거래가 덜 잡힌 **잠정치**입니다.
 * 막대에 "(잠정)"을 붙여 거래 급감처럼 보이는 착시를 막습니다.
 */
export default function HousingPage() {
  const { data, loading, error, reload } = useApi<SeoulHousingResponse>("/api/housing/seoul");
  const months = data?.months ?? [];
  const reference = months.find((month) => month.month === data?.referenceMonth);
  const manwonToWon = (value: number | null | undefined) =>
    value === null || value === undefined ? null : value * 10_000;

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">🏠 서울 아파트 실거래</h1>
        <p className="mt-1 text-sm text-muted">
          {data?.source ?? "국토교통부 아파트 매매 실거래가"} · 평당가 = 거래금액 ÷ 전용면적 × 3.3058 · 중위값
        </p>
      </header>

      {loading && !data && <Loading label="실거래 통계를 계산하는 중…" />}
      {error && <ErrorState message={error} onRetry={reload} />}
      {data && !data.available && <Banner tone="info">{data.message}</Banner>}

      {data?.available && (
        <>
          <Banner tone="info">{data.note}</Banner>

          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Metric label="기준월 (잠정 아님)" value={data.referenceMonth ?? EMPTY} />
            <Metric label="서울 거래량" value={reference ? `${formatNumber(reference.count, 0)}건` : EMPTY} />
            <Metric
              label="평당가 중위값"
              value={reference?.medianPricePerPyeong ? `${formatNumber(reference.medianPricePerPyeong, 0)}만 원` : EMPTY}
            />
            <Metric label="거래금액 중위값" value={formatKrw(manwonToWon(reference?.medianAmount))} />
          </div>

          <Card title="월별 거래량 (서울 전체)" subtitle="막대 이름의 (잠정)은 신고 기한이 남은 달, (n/25)는 모인 구 수">
            <SignedBars
              data={months.map((month) => ({
                name: `${month.month}${month.provisional ? " (잠정)" : ""}${month.coverage < 25 ? ` (${month.coverage}/25)` : ""}`,
                value: month.count,
              }))}
              unit="건"
            />
          </Card>

          <Card title="평당가 중위값 추이 (만 원/평)" subtitle="모든 구의 거래를 합친 중위값 — 구별 중위값의 평균이 아닙니다">
            <LineSeries
              data={months.map((month) => ({ date: month.month, value: month.medianPricePerPyeong ?? null }))}
              color={SERIES_COLORS.blue}
              unit="만"
              precision={0}
            />
          </Card>

          <Card title={`구별 비교 — ${data.referenceMonth ?? ""}`} subtitle="평당가 높은 순 · 전년 동월 대비(계절 효과 제거)">
            <Table
              rows={data.districts}
              rowKey={(row) => row.lawd}
              columns={[
                { key: "name", header: "구", render: (row) => row.name ?? row.lawd },
                { key: "count", header: "거래량", align: "right", render: (row) => `${formatNumber(row.count, 0)}건` },
                {
                  key: "countYoy",
                  header: "1년 전 거래량",
                  align: "right",
                  render: (row) => (row.countYearAgo === null ? EMPTY : `${formatNumber(row.countYearAgo, 0)}건`),
                },
                {
                  key: "price",
                  header: "평당가 중위",
                  align: "right",
                  render: (row) => (row.medianPricePerPyeong === null ? EMPTY : `${formatNumber(row.medianPricePerPyeong, 0)}만`),
                },
                {
                  key: "yoy",
                  header: "평당가 전년比",
                  align: "right",
                  render: (row) => <span className={deltaColor(row.priceYoyPct)}>{formatPercent(row.priceYoyPct)}</span>,
                },
                { key: "amount", header: "거래금액 중위", align: "right", render: (row) => formatKrw(manwonToWon(row.medianAmount)) },
              ]}
            />
          </Card>
        </>
      )}
    </div>
  );
}
