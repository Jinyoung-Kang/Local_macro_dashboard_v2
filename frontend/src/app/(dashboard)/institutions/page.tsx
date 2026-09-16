"use client";

import { useState } from "react";
import { MultiLineSeries } from "@/components/charts";
import {
  Banner,
  Card,
  EmptyState,
  ErrorState,
  Freshness,
  Loading,
  Metric,
  Select,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { deltaColor, EMPTY, formatCurrency, formatNumber } from "@/lib/format";
import type { PortfolioResponse } from "@/lib/types";

const SERIES_COLORS = [
  "#58A6FF", "#3FB950", "#D29922", "#F85149", "#A371F7",
  "#39C5CF", "#DB6D28", "#8B949E", "#7EE787", "#FF7B72",
];

/**
 * 📑 기관 13F 포트폴리오 분석.
 *
 * ⚠️ 13F는 분기 공시이며 **45일 지연**입니다. 지금의 포지션이 아니라 지난
 * 분기말 스냅샷이라는 점을 화면이 계속 상기시켜야 합니다.
 */
export default function InstitutionsPage() {
  const institutions = useApi<{ institutions: { key: string; name: string; cik: string; desc: string }[] }>(
    "/api/sec13f/institutions",
  );
  const [cik, setCik] = useState("0001067983");   // 버크셔 해서웨이
  const [quarters, setQuarters] = useState("8");
  const [topN, setTopN] = useState("30");

  const { data, loading, error, reload } = useApi<PortfolioResponse>(
    `/api/sec13f/portfolio?cik=${cik}&quarters=${quarters}&topN=${topN}`,
  );

  const selected = institutions.data?.institutions.find((entry) => entry.cik === cik);
  const history = data?.weightHistory;

  const chartData =
    history?.dates.map((date, index) => {
      const row: Record<string, string | number> = { date };
      Object.entries(history.series).forEach(([name, values]) => {
        row[name] = values[index] ?? 0;
      });
      return row;
    }) ?? [];

  const chartSeries = Object.keys(history?.series ?? {})
    .slice(0, 10)
    .map((name, index) => ({
      key: name,
      name: name.length > 18 ? `${name.slice(0, 18)}…` : name,
      color: SERIES_COLORS[index % SERIES_COLORS.length],
    }));

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-bright">📑 기관 13F 포트폴리오 분석</h1>
          <p className="mt-1 text-xs text-muted">
            SEC EDGAR 공식 공시 · 분기 공시, 공시 마감 45일 지연
          </p>
        </div>
        <Freshness collectedAt={data?.collectedAtKst} ageSeconds={data?.ageSeconds} />
      </header>

      <Card>
        <div className="flex flex-wrap items-end gap-3">
          <Select
            label="분석할 기관"
            value={cik}
            onChange={setCik}
            options={(institutions.data?.institutions ?? []).map((entry) => ({
              value: entry.cik,
              label: entry.name,
            }))}
          />
          <Select
            label="조회 분기 수"
            value={quarters}
            onChange={setQuarters}
            options={["1", "2", "4", "8"].map((value) => ({
              value,
              label: `최근 ${value}개 분기`,
            }))}
          />
          <Select
            label="표시 종목 수"
            value={topN}
            onChange={setTopN}
            options={["10", "20", "30", "50"].map((value) => ({
              value,
              label: `상위 ${value}개`,
            }))}
          />
        </div>
        {selected?.desc && <p className="mt-3 text-xs text-muted">{selected.desc}</p>}
      </Card>

      {loading && !data && <Loading label="13F 공시를 불러오는 중…" />}
      {error && <ErrorState message={error} onRetry={reload} />}

      {data && !data.available && (
        <Banner tone="warn">
          {data.error ?? data.message ?? "13F 데이터가 없습니다. 수집기의 weekly 작업을 실행하세요."}
        </Banner>
      )}

      {data?.available && (
        <>
          <div className="grid gap-3 sm:grid-cols-3">
            <Metric
              label="최신 공시 기준일"
              value={data.latest?.reportDate ?? EMPTY}
              caption={`제출일 ${data.latest?.filingDate ?? EMPTY}`}
            />
            <Metric
              label="포트폴리오 총액"
              value={formatCurrency(data.latest?.totalValue ?? null)}
            />
            <Metric
              label="수집된 분기"
              value={`${data.quarters.length}개`}
              caption={data.quarters.map((q) => q.reportDate).join(", ")}
            />
          </div>

          <Card
            title="📈 상위 종목 분기별 비중 추이"
            subtitle="보유하지 않은 분기는 0%입니다(데이터 없음이 아니라 미보유)."
          >
            {chartSeries.length === 0 ? (
              <EmptyState message="비중 추이를 그릴 분기 데이터가 부족합니다." />
            ) : (
              <MultiLineSeries data={chartData} series={chartSeries} unit="%" height={320} />
            )}
          </Card>

          <Card
            title={`📋 보유 종목 상세 (기준일 ${data.latest?.reportDate ?? EMPTY})`}
            subtitle="직전 분기 대비 액션은 비중 변화 ±0.05%p를 기준으로 분류합니다."
          >
            <Table
              rows={data.holdings ?? []}
              rowKey={(row, index) => `${row.cusip}-${index}`}
              columns={[
                {
                  key: "name",
                  header: "종목",
                  render: (row) => (
                    <span className="flex flex-col">
                      <span className="text-body">{row.name}</span>
                      <span className="text-[11px] text-muted">
                        CUSIP {row.cusip} · {row.class}
                      </span>
                    </span>
                  ),
                },
                {
                  key: "weight",
                  header: "비중",
                  align: "right",
                  render: (row) => `${formatNumber(row.weight, 2)}%`,
                },
                {
                  key: "weightDiff",
                  header: "비중 변화",
                  align: "right",
                  render: (row) => (
                    <span className={deltaColor(row.weightDiff)}>
                      {row.weightDiff === null ? EMPTY : `${formatNumber(row.weightDiff, 2)}%p`}
                    </span>
                  ),
                },
                {
                  key: "value",
                  header: "평가액",
                  align: "right",
                  render: (row) => formatCurrency(row.value),
                },
                {
                  key: "shares",
                  header: "보유 주식수",
                  align: "right",
                  render: (row) => formatNumber(row.shares, 0),
                },
                {
                  key: "action",
                  header: "직전 분기 대비",
                  render: (row) => <SourceBadge>{row.action}</SourceBadge>,
                },
              ]}
            />
          </Card>
        </>
      )}
    </div>
  );
}
