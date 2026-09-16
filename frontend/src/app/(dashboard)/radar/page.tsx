"use client";

import { useState } from "react";
import { HorizontalBars } from "@/components/charts";
import {
  Banner,
  Button,
  Card,
  ErrorState,
  Freshness,
  Loading,
  Select,
  SourceBadge,
  Table,
} from "@/components/ui";
import { useApi } from "@/hooks/useApi";
import { deltaColor, EMPTY, formatNumber, formatPercent } from "@/lib/format";
import type { DiagnosticsResponse, RadarResponse } from "@/lib/types";

/**
 * 📡 외국인/기관 수급 레이더.
 *
 * 수집 순서(KIS → Daum → Naver → LS → PyKrx → 누적 이력)는 수집기가 관리하고,
 * 화면은 <b>어느 출처가 실제로 성공했는지</b>를 반드시 표시합니다. 누적 이력으로
 * 대체된 경우에는 "지금 시점의 수급이 아니다"라는 경고가 함께 떠야 합니다.
 */
export default function RadarPage() {
  const options = useApi<{
    markets: string[];
    investors: string[];
    tradeTypes: string[];
    intervals: string[];
  }>("/api/radar/options");

  const [market, setMarket] = useState("KOSPI");
  const [investor, setInvestor] = useState("외국인");
  const [tradeType, setTradeType] = useState("순매수");
  const [interval, setInterval] = useState("TODAY");
  const [topN, setTopN] = useState("30");
  const [showDiagnostics, setShowDiagnostics] = useState(false);

  const query =
    `/api/radar/ranking?market=${market}&investor=${encodeURIComponent(investor)}` +
    `&tradeType=${encodeURIComponent(tradeType)}&intervalType=${interval}&topN=${topN}`;

  const { data, loading, error, reload } = useApi<RadarResponse>(query, 60_000);

  const chartData = (data?.rows ?? [])
    .slice(0, 15)
    .map((row) => ({ name: row.name, value: row.netAmountEok ?? 0 }));

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-bright">📡 외국인/기관 수급 레이더</h1>
          <p className="mt-1 text-xs text-muted">
            폴백 체인: KIS(장중) → Daum → Naver → LS → PyKrx → 누적 이력
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Freshness collectedAt={data?.collectedAtKst} stale={data?.stale} />
          <Button onClick={reload}>새로고침</Button>
          <Button onClick={() => setShowDiagnostics((value) => !value)}>
            {showDiagnostics ? "진단 닫기" : "데이터 소스 연결 테스트"}
          </Button>
        </div>
      </header>

      {showDiagnostics && <DiagnosticsPanel />}

      <Card>
        <div className="flex flex-wrap items-end gap-3">
          <Select
            label="시장"
            value={market}
            onChange={setMarket}
            options={(options.data?.markets ?? ["KOSPI"]).map((value) => ({ value, label: value }))}
          />
          <Select
            label="투자 주체"
            value={investor}
            onChange={setInvestor}
            options={(options.data?.investors ?? ["외국인"]).map((value) => ({
              value,
              label: value,
            }))}
          />
          <Select
            label="매매 구분"
            value={tradeType}
            onChange={setTradeType}
            options={(options.data?.tradeTypes ?? ["순매수"]).map((value) => ({
              value,
              label: value,
            }))}
          />
          <Select
            label="조회 기간"
            value={interval}
            onChange={setInterval}
            options={(options.data?.intervals ?? ["TODAY"]).map((value) => ({
              value,
              label: { TODAY: "당일", DAYS_5: "5거래일", DAYS_20: "20거래일" }[value] ?? value,
            }))}
          />
          <Select
            label="표시 종목 수"
            value={topN}
            onChange={setTopN}
            options={["10", "20", "30", "50"].map((value) => ({ value, label: `상위 ${value}개` }))}
          />
        </div>
      </Card>

      {loading && !data && <Loading label="수급 데이터를 불러오는 중…" />}
      {error && <ErrorState message={error} onRetry={reload} />}

      {data?.warning && <Banner tone="warn">⚠️ {data.warning}</Banner>}

      {data && !data.available && !data.warning && (
        <Banner tone="warn">
          {data.message ?? "수급 데이터를 얻지 못했습니다. 수집기 상태를 확인하세요."}
        </Banner>
      )}

      {data?.available && (
        <>
          <Card
            title={`${investor} ${tradeType} 상위`}
            subtitle={data.source ?? undefined}
            actions={
              data.sourceKind ? <SourceBadge>출처: {data.sourceKind}</SourceBadge> : undefined
            }
          >
            <HorizontalBars
              data={chartData}
              unit="억"
              height={Math.max(260, chartData.length * 26)}
            />
          </Card>

          <Card title="📋 상세 목록">
            <Table
              rows={data.rows}
              rowKey={(row) => `${row.code}-${row.rank}`}
              columns={[
                { key: "rank", header: "순위", render: (row) => row.rank },
                {
                  key: "name",
                  header: "종목",
                  render: (row) => (
                    <span className="flex flex-col">
                      <span className="text-body">{row.name}</span>
                      {/* 종목코드는 앞자리 0을 포함한 문자열 그대로 표시합니다. */}
                      <span className="text-[11px] text-muted">{row.code}</span>
                    </span>
                  ),
                },
                {
                  key: "price",
                  header: "현재가",
                  align: "right",
                  render: (row) => formatNumber(row.price, 0),
                },
                {
                  key: "changePct",
                  header: "등락률",
                  align: "right",
                  render: (row) => (
                    <span className={deltaColor(row.changePct)}>
                      {formatPercent(row.changePct)}
                    </span>
                  ),
                },
                {
                  key: "net",
                  header: "순매수대금(억)",
                  align: "right",
                  render: (row) => (
                    <span className={deltaColor(row.netAmountEok, 1)}>
                      {formatNumber(row.netAmountEok, 1)}
                    </span>
                  ),
                },
              ]}
            />
          </Card>
        </>
      )}

      <HistoryPanel market={market} investor={investor} tradeType={tradeType} />
    </div>
  );
}

function DiagnosticsPanel() {
  const { data, loading, reload } = useApi<DiagnosticsResponse>("/api/radar/diagnostics");

  return (
    <Card
      title="🔌 데이터 소스 연결 진단"
      subtitle="화면이 실제로 쓰는 경로를 그대로 호출합니다."
      actions={<Button onClick={reload}>다시 검사</Button>}
    >
      {loading && !data && <Loading />}
      {data && !data.available && <Banner tone="warn">{data.message}</Banner>}
      {data?.sources && (
        <Table
          rows={Object.entries(data.sources).map(([name, value]) => ({ name, ...value }))}
          rowKey={(row) => row.name}
          columns={[
            { key: "name", header: "소스", render: (row) => row.name.toUpperCase() },
            {
              key: "status",
              header: "상태",
              render: (row) => (
                <span className={row.ok ? "text-ok" : "text-danger"}>
                  {row.ok ? "✅ 정상" : "❌ 실패"}
                </span>
              ),
            },
            { key: "stage", header: "단계", render: (row) => <SourceBadge>{row.stage}</SourceBadge> },
            { key: "message", header: "메시지", render: (row) => row.message },
          ]}
        />
      )}
    </Card>
  );
}

function HistoryPanel({
  market,
  investor,
  tradeType,
}: {
  market: string;
  investor: string;
  tradeType: string;
}) {
  const { data, loading } = useApi<{
    dates: string[];
    note: string;
    rows: { obsDate: string; code: string; name: string; netAmountEok: number }[];
  }>(
    `/api/radar/history?market=${market}&investor=${encodeURIComponent(
      investor,
    )}&tradeType=${encodeURIComponent(tradeType)}`,
  );

  const [date, setDate] = useState("");
  const dates = data?.dates ?? [];
  const selectedDate = date || dates[0] || "";
  const rows = (data?.rows ?? []).filter((row) => row.obsDate === selectedDate).slice(0, 30);

  return (
    <Card
      title="🗂️ 누적 수급 이력"
      subtitle={data?.note}
      actions={
        dates.length > 0 ? (
          <Select
            label="거래일"
            value={selectedDate}
            onChange={setDate}
            options={dates.map((value) => ({ value, label: value }))}
          />
        ) : undefined
      }
    >
      {loading && !data && <Loading />}
      {!loading && dates.length === 0 && (
        <Banner tone="info">
          아직 누적된 이력이 없습니다. 수집기를 꾸준히 돌리면 거래일별로 쌓입니다 —
          Naver·Daum·KRX는 과거 날짜 조회를 지원하지 않으므로 이 이력이 곧 백업입니다.
        </Banner>
      )}
      {rows.length > 0 && (
        <Table
          rows={rows}
          rowKey={(row, index) => `${row.code}-${index}`}
          columns={[
            { key: "code", header: "종목코드", render: (row) => row.code },
            { key: "name", header: "종목명", render: (row) => row.name },
            {
              key: "net",
              header: "순매수대금(억)",
              align: "right",
              render: (row) => (
                <span className={deltaColor(row.netAmountEok, 1)}>
                  {formatNumber(row.netAmountEok, 1)}
                </span>
              ),
            },
          ]}
        />
      )}
      {!loading && dates.length > 0 && rows.length === 0 && (
        <p className="text-xs text-muted">{EMPTY} 해당 조건의 이력이 없습니다.</p>
      )}
    </Card>
  );
}
