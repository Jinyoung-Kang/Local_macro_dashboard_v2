"use client";

import { useState } from "react";
import { MultiLineSeries } from "@/components/charts";
import {
  Banner,
  Card,
  ErrorState,
  Freshness,
  Loading,
  Metric,
  Select,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { deltaColor, EMPTY, formatNumber, formatSigned } from "@/lib/format";
import type { CotAssetResponse, CotSummary } from "@/lib/types";

/**
 * 🏛️ 글로벌 투기세력 (CFTC COT).
 *
 * ⚠️ CFTC는 화요일 기준 포지션을 금요일에 공시합니다. 항상 며칠 지난
 * 데이터이므로 지연 일수를 함께 표시합니다.
 */
export default function CotPage() {
  const assets = useApi<{ assets: { name: string; code: string; category: string }[] }>(
    "/api/cot/assets",
  );
  const overview = useApi<{ available: boolean; collectedAtKst?: string; assets: CotSummary[] }>(
    "/api/cot/overview",
    600_000,
  );
  const [asset, setAsset] = useState("S&P 500 E-Mini");

  const detail = useApi<CotAssetResponse>(
    `/api/cot/asset?name=${encodeURIComponent(asset)}`,
  );

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-bright">🏛️ 글로벌 투기세력 (COT)</h1>
          <p className="mt-1 text-xs text-muted">
            CFTC 공개 데이터 · 비상업(투기) / 상업(헤저) / 비보고(소액) 순포지션
          </p>
        </div>
        <Freshness collectedAt={overview.data?.collectedAtKst} />
      </header>

      {overview.data && !overview.data.available && (
        <Banner tone="warn">COT 저장본이 없습니다. 수집기의 slow 작업을 실행하세요.</Banner>
      )}

      <Card title="자산별 요약" subtitle="스마트머니(비상업) 순포지션과 최근 변화">
        {overview.loading && !overview.data && <Loading />}
        {overview.data && (
          <Table
            rows={overview.data.assets ?? []}
            rowKey={(row) => row.asset}
            columns={[
              {
                key: "asset",
                header: "자산",
                render: (row) => (
                  <span className="flex flex-col">
                    <span className="text-body">{row.asset}</span>
                    <span className="text-[11px] text-muted">{row.category}</span>
                  </span>
                ),
              },
              {
                key: "date",
                header: "기준일",
                render: (row) =>
                  row.available ? (
                    <span className="flex flex-col">
                      <span>{row.date}</span>
                      <span className="text-[11px] text-muted">{row.ageDays}일 전 공시</span>
                    </span>
                  ) : (
                    <span className="text-muted">{row.error ?? "데이터 없음"}</span>
                  ),
              },
              {
                key: "ncNet",
                header: "스마트머니 순포지션",
                align: "right",
                render: (row) => (
                  <span className={deltaColor(row.ncNet ?? null, 0)}>
                    {row.ncNet === null || row.ncNet === undefined
                      ? EMPTY
                      : formatSigned(row.ncNet, 0)}
                  </span>
                ),
              },
              {
                key: "commNet",
                header: "상업 헤저",
                align: "right",
                render: (row) =>
                  row.commNet === null || row.commNet === undefined
                    ? EMPTY
                    : formatSigned(row.commNet, 0),
              },
              {
                key: "change1w",
                header: "1주 변화",
                align: "right",
                render: (row) => (
                  <span className={deltaColor(row.change1w ?? null, 0)}>
                    {row.change1w === null || row.change1w === undefined
                      ? EMPTY
                      : formatSigned(row.change1w, 0)}
                  </span>
                ),
              },
              {
                key: "change4w",
                header: "4주 변화",
                align: "right",
                render: (row) => (
                  <span className={deltaColor(row.change4w ?? null, 0)}>
                    {row.change4w === null || row.change4w === undefined
                      ? EMPTY
                      : formatSigned(row.change4w, 0)}
                  </span>
                ),
              },
              {
                key: "percentile",
                header: "3년 백분위",
                align: "right",
                render: (row) =>
                  row.percentile === null || row.percentile === undefined
                    ? EMPTY
                    : `${formatNumber(row.percentile, 1)}%`,
              },
            ]}
          />
        )}
      </Card>

      <Card
        title="포지셔닝 추이"
        actions={
          <Select
            label="자산"
            value={asset}
            onChange={setAsset}
            options={(assets.data?.assets ?? []).map((entry) => ({
              value: entry.name,
              label: entry.name,
            }))}
          />
        }
      >
        {detail.loading && !detail.data && <Loading />}
        {detail.error && <ErrorState message={detail.error} onRetry={detail.reload} />}
        {detail.data && !detail.data.available && (
          <Banner tone="warn">{detail.data.message ?? "해당 자산의 데이터가 없습니다."}</Banner>
        )}
        {detail.data?.available && (
          <>
            <div className="mb-4 grid gap-3 sm:grid-cols-4">
              <Metric
                label="스마트머니 순포지션"
                value={formatSigned(detail.data.summary?.ncNet ?? null, 0)}
                delta={detail.data.summary?.change1w ?? null}
                deltaText={`1주 ${formatSigned(detail.data.summary?.change1w ?? null, 0)}`}
                caption={`기준일 ${detail.data.summary?.date ?? EMPTY}`}
              />
              <Metric
                label="상업 헤저 순포지션"
                value={formatSigned(detail.data.summary?.commNet ?? null, 0)}
              />
              <Metric
                label="소액/비보고"
                value={formatSigned(detail.data.summary?.nrNet ?? null, 0)}
              />
              <Metric
                label="3년 표본 백분위"
                value={
                  detail.data.summary?.percentile === null ||
                  detail.data.summary?.percentile === undefined
                    ? EMPTY
                    : `${formatNumber(detail.data.summary.percentile, 1)}%`
                }
                caption="100%에 가까울수록 역사적 최대 롱"
              />
            </div>

            <MultiLineSeries
              data={detail.data.rows.map((row) => ({
                date: row.date,
                스마트머니: row.ncNet,
                상업헤저: row.commNet,
                소액: row.nrNet,
              }))}
              series={[
                { key: "스마트머니", name: "비상업 (투기)", color: "#58A6FF" },
                { key: "상업헤저", name: "상업 (헤저)", color: "#D29922" },
                { key: "소액", name: "비보고 (소액)", color: "#8B949E" },
              ]}
              height={320}
            />
            <p className="mt-3 text-xs text-muted">
              <SourceBadge>계약 코드 {detail.data.code}</SourceBadge> 비상업과 상업은 구조적으로
              반대 방향입니다. 비상업이 역사적 극단에 도달하면 되돌림 위험이 커집니다.
            </p>
          </>
        )}
      </Card>
    </div>
  );
}
