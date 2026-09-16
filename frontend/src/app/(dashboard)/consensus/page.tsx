"use client";

import { useState } from "react";
import { HorizontalBars } from "@/components/charts";
import {
  Banner,
  Card,
  ErrorState,
  Loading,
  Metric,
  Select,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { EMPTY, formatCurrency, formatNumber } from "@/lib/format";
import type { ConsensusResponse } from "@/lib/types";

/**
 * 🎯 기관 13F Money 교집합.
 *
 * 여러 기관이 같은 분기에 공통 보유한 종목과, 동시 순매수/순매도가 몰린
 * 종목을 집계합니다. 기관마다 공시 분기가 다를 수 있으므로 기준 분기를
 * 고르면 그 분기를 공시한 기관만 참여합니다.
 */
export default function ConsensusPage() {
  const institutions = useApi<{ institutions: { name: string; cik: string }[] }>(
    "/api/sec13f/institutions",
  );
  const [selected, setSelected] = useState<string[]>([]);
  const [reportDate, setReportDate] = useState("");
  const [minHolders, setMinHolders] = useState("2");

  const ciks = selected.length > 0 ? selected.join(",") : "";
  const { data, loading, error, reload } = useApi<ConsensusResponse>(
    `/api/sec13f/consensus?minHolders=${minHolders}&topN=40${
      ciks ? `&ciks=${ciks}` : ""
    }${reportDate ? `&reportDate=${reportDate}` : ""}`,
  );

  const toggle = (cik: string) => {
    setSelected((previous) =>
      previous.includes(cik) ? previous.filter((item) => item !== cik) : [...previous, cik],
    );
  };

  const chartData = (data?.rows ?? [])
    .slice(0, 15)
    .map((row) => ({
      name: row.name.length > 16 ? `${row.name.slice(0, 16)}…` : row.name,
      value: row.holderCount,
    }));

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="text-xl font-bold text-bright">🎯 기관 13F Money 교집합</h1>
        <p className="mt-1 text-xs text-muted">
          여러 기관이 공통 보유한 종목 · 동시 매수/매도 집중도
        </p>
      </header>

      <Card title="분석 대상" subtitle="선택하지 않으면 전체 기관을 비교합니다.">
        <div className="flex flex-wrap gap-2">
          {(institutions.data?.institutions ?? []).map((entry) => {
            const active = selected.includes(entry.cik);
            return (
              <button
                key={entry.cik}
                type="button"
                onClick={() => toggle(entry.cik)}
                className={`rounded-md border px-3 py-1.5 text-xs transition ${
                  active
                    ? "border-accent/60 bg-accent/15 text-accent"
                    : "border-border bg-surface-hover text-muted hover:text-bright"
                }`}
              >
                {entry.name}
              </button>
            );
          })}
        </div>

        <div className="mt-4 flex flex-wrap items-end gap-3">
          <Select
            label="기준 분기"
            value={reportDate}
            onChange={setReportDate}
            options={[
              { value: "", label: "각 기관의 최신 분기" },
              ...(data?.availableDates ?? []).map((date) => ({ value: date, label: date })),
            ]}
          />
          <Select
            label="최소 공통 보유 기관 수"
            value={minHolders}
            onChange={setMinHolders}
            options={["2", "3", "4", "5"].map((value) => ({
              value,
              label: `${value}곳 이상`,
            }))}
          />
        </div>
      </Card>

      {loading && !data && <Loading label="교집합을 계산하는 중…" />}
      {error && <ErrorState message={error} onRetry={reload} />}

      {data && !data.available && (
        <Banner tone="warn">
          조건을 만족하는 공통 보유 종목이 없습니다. 기준 분기나 최소 기관 수를 조정해 보세요.
        </Banner>
      )}

      {data?.available && (
        <>
          <div className="grid gap-3 sm:grid-cols-3">
            <Metric label="참여 기관" value={`${data.participantCount}곳`} />
            <Metric label="교집합 종목" value={`${data.rows.length}개`} />
            <Metric
              label="기준 분기"
              value={data.reportDate ?? "각 기관 최신"}
            />
          </div>

          <Card title="📊 공통 보유 상위 종목" subtitle="막대 길이 = 보유 기관 수">
            <HorizontalBars data={chartData} unit="곳" height={Math.max(260, chartData.length * 26)} />
          </Card>

          <Card title="📋 교집합 상세">
            <Table
              rows={data.rows}
              rowKey={(row) => row.name}
              columns={[
                { key: "name", header: "종목", render: (row) => row.name },
                {
                  key: "holderCount",
                  header: "보유 기관",
                  align: "right",
                  render: (row) => `${row.holderCount}곳`,
                },
                {
                  key: "buy",
                  header: "동시 매수",
                  align: "right",
                  render: (row) => (
                    <span className={row.buyCount > 0 ? "text-up" : "text-muted"}>
                      {row.buyCount}
                    </span>
                  ),
                },
                {
                  key: "sell",
                  header: "동시 매도",
                  align: "right",
                  render: (row) => (
                    <span className={row.sellCount > 0 ? "text-down" : "text-muted"}>
                      {row.sellCount}
                    </span>
                  ),
                },
                {
                  key: "avgWeight",
                  header: "평균 비중",
                  align: "right",
                  render: (row) => `${formatNumber(row.avgWeight, 2)}%`,
                },
                {
                  key: "maxWeight",
                  header: "최대 비중",
                  align: "right",
                  render: (row) => `${formatNumber(row.maxWeight, 2)}%`,
                },
                {
                  key: "totalValue",
                  header: "합산 평가액",
                  align: "right",
                  render: (row) => formatCurrency(row.totalValue),
                },
                {
                  key: "holders",
                  header: "보유 기관 목록",
                  render: (row) => (
                    <span className="flex flex-wrap gap-1">
                      {row.holders.map((holder) => (
                        <SourceBadge key={holder}>{holder.split(" ")[0] ?? holder}</SourceBadge>
                      ))}
                    </span>
                  ),
                },
              ]}
            />
          </Card>

          <p className="text-xs text-muted">
            참여 기관: {data.participants.join(", ") || EMPTY}
          </p>
        </>
      )}
    </div>
  );
}
