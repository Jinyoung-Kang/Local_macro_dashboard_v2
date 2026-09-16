"use client";

import { useState } from "react";
import { LineSeries } from "@/components/charts";
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
import { deltaColor, EMPTY, formatNumber, formatPercent, formatSigned, statusColor } from "@/lib/format";
import type {
  AdvancedIndicators,
  MacroOverview,
  RiskEntry,
  RiskIndicators,
} from "@/lib/types";

const SPREAD_TABLE = [
  {
    state: "정상 (Normal)",
    value: "양수 (+)",
    reading: "장기 미래의 불확실성(프리미엄)으로 장기 금리가 더 높습니다.",
    outcome: "경제의 점진적인 성장 및 확장",
  },
  {
    state: "평탄화 (Flattening)",
    value: "0에 수렴",
    reading: "미래 경기 성장이 둔화될 것이라는 우려가 커지기 시작합니다.",
    outcome: "경기 정점 통과 및 둔화 신호",
  },
  {
    state: "역전 (Inversion) ⚠️",
    value: "음수 (−)",
    reading: "현재 인플레이션을 잡기 위해 금리를 올렸으나 미래 경기 침체를 확신합니다.",
    outcome: "역사적으로 1~2년 내 경기 침체 도래",
  },
];

const RISK_TABLE = [
  {
    name: "CBOE VIX [15분 지연]",
    normal: "15 ~ 20 (15 미만: 과도한 낙관)",
    danger: "30 이상 (패닉 / 급락 / 투매)",
    note: "주식 시장의 단기 공포 측정기. 급등 시 주가 급락·투매 신호.",
  },
  {
    name: "ICE BofA MOVE [지연/마감]",
    normal: "80 ~ 120 (80 미만: 금리 초안정)",
    danger: "140 이상 (채권 발작 / 긴축 충격)",
    note: "채권 시장의 공포 지수. ⚠️ 이 화면의 값은 추정치이므로 이 임계치를 그대로 적용하지 마세요.",
  },
  {
    name: "하이일드 스프레드 [1일 지연]",
    normal: "3.5% ~ 5.0%",
    danger: "7.0% 이상 (본격 신용경색)",
    note: "한계 기업 부도 리스크 프리미엄. 침체 진입 시 가장 먼저 급등하는 선행 지표.",
  },
  {
    name: "3M 금융 CP 스프레드 [1일 지연]",
    normal: "0.20%p ~ 0.50%p",
    danger: "0.80%p 이상 (단기 자금시장 경색)",
    note: "은행권 3개월 단기 자금조달 가산금리(현대판 TED 스프레드).",
  },
  {
    name: "STLFSI4 금융스트레스 [주간]",
    normal: "0.0 이하 (장기 평균)",
    danger: "+1.0 이상 (시스템 위기 경보)",
    note: "18개 금융시장 지표를 종합한 복합 척도.",
  },
];

export default function MacroPage() {
  const overview = useApi<MacroOverview>("/api/macro/overview", 60_000);
  const risk = useApi<RiskIndicators>("/api/macro/risk", 120_000);
  const advanced = useApi<AdvancedIndicators>("/api/macro/advanced", 300_000);

  if (overview.loading && !overview.data) {
    return <Loading label="매크로 지표를 불러오는 중…" />;
  }
  if (overview.error) {
    return <ErrorState message={overview.error} onRetry={overview.reload} />;
  }

  const data = overview.data;

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-bright">📊 거시경제 매크로 지표</h1>
          <p className="mt-1 text-xs text-muted">
            환율·국채·원자재·지수, 장단기 금리차, 신용 리스크, 심화 지표 5종
          </p>
        </div>
        <Freshness
          collectedAt={data?.collectedAtKst}
          ageSeconds={data?.ageSeconds}
          stale={data?.stale}
        />
      </header>

      {!data?.available && (
        <Banner tone="warn">
          {data?.message ?? "매크로 데이터가 없습니다. 수집기를 실행하세요."}
        </Banner>
      )}

      {data?.categories?.map((category) => (
        <Card
          key={category.id}
          title={category.title}
          subtitle={category.note ? `데이터 지연: ${category.note}` : undefined}
        >
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {category.items.map((item) => (
              <Metric
                key={item.key}
                label={
                  <span className="flex items-center gap-1">
                    {item.name}
                    {item.note && <SourceBadge>{item.note}</SourceBadge>}
                  </span>
                }
                value={item.status === "fail" ? "수집 실패" : item.priceStr ?? EMPTY}
                delta={item.delta}
                deltaText={item.deltaStr ?? EMPTY}
                tone={item.status === "fail" ? "text-muted" : undefined}
                caption={
                  <span className="flex flex-col gap-0.5">
                    <span>직전: {item.prevStr ?? EMPTY}</span>
                    {item.lastTs && <span>{item.lastTs}</span>}
                    {item.prevSource && <span>전일값 출처: {item.prevSource}</span>}
                    {item.source && <span>{item.source}</span>}
                  </span>
                }
              />
            ))}
          </div>
        </Card>
      ))}

      {data?.spreads && (
        <>
          <SpreadSection
            title="📊 공식 일별 10Y−2Y 장단기 금리차"
            realtime={data.spreads.realtime}
            block={data.spreads.official10y2y}
          />
          <SpreadSection
            title="📊 공식 일별 30Y−2Y 장단기 금리차"
            block={data.spreads.official30y2y}
          />
          <Card
            title="📖 장단기 금리차 해석 기준"
            subtitle="역사적 분포에 근거한 참고치이며 투자 판단의 근거가 아닙니다."
          >
            <Table
              rows={SPREAD_TABLE}
              rowKey={(row) => row.state}
              columns={[
                { key: "state", header: "시장 상태", render: (row) => row.state },
                { key: "value", header: "스프레드", render: (row) => row.value },
                { key: "reading", header: "시장의 심리 및 해석", render: (row) => row.reading },
                { key: "outcome", header: "경제적 귀결", render: (row) => row.outcome },
              ]}
            />
          </Card>
        </>
      )}

      <RiskSection risk={risk.data} loading={risk.loading} />
      <AdvancedSection data={advanced.data} loading={advanced.loading} />
      <SingleChartSection />
      <ScrapedSection />
    </div>
  );
}

function SpreadSection({
  title,
  realtime,
  block,
}: {
  title: string;
  realtime?: MacroOverview["spreads"] extends undefined
    ? never
    : NonNullable<MacroOverview["spreads"]>["realtime"];
  block: NonNullable<MacroOverview["spreads"]>["official10y2y"];
}) {
  const points = block?.points ?? [];
  const inverted = (block?.latest ?? 0) < 0;

  return (
    <Card
      title={title}
      subtitle={`FRED ${block?.longId} − ${block?.shortId} (미 재무부 공식 일별 확정치)`}
    >
      <div className="grid gap-3 sm:grid-cols-3">
        {realtime && (
          <>
            <Metric
              label="실시간 10Y−2Y 스프레드"
              value={realtime.spread === null ? "수집 실패" : `${formatSigned(realtime.spread, 3)}%p`}
              delta={realtime.delta}
              deltaText={
                realtime.delta === null ? EMPTY : `${formatSigned(realtime.delta, 3)}%p`
              }
              caption="TradingView 참고 수익률 기준"
            />
            <Metric
              label="미국채 10년물"
              value={realtime.us10y === null ? EMPTY : `${formatNumber(realtime.us10y, 3)}%`}
            />
            <Metric
              label="미국채 2년물"
              value={realtime.us02y === null ? EMPTY : `${formatNumber(realtime.us02y, 3)}%`}
            />
          </>
        )}
        {!realtime && (
          <Metric
            label="공식 일별 스프레드 (최신)"
            value={block?.latest === null ? EMPTY : `${formatSigned(block?.latest ?? null, 3)}%p`}
            delta={
              block?.latest !== null && block?.previous !== null
                ? (block?.latest ?? 0) - (block?.previous ?? 0)
                : null
            }
            deltaText={
              block?.latest !== null && block?.previous !== null
                ? `${formatSigned((block?.latest ?? 0) - (block?.previous ?? 0), 3)}%p`
                : EMPTY
            }
          />
        )}
      </div>

      {inverted && (
        <div className="mt-4">
          <Banner tone="danger">
            현재 스프레드가 <strong>역전(음수)</strong> 상태입니다. 역사적으로 1~2년 내
            침체가 뒤따른 구간입니다.
          </Banner>
        </div>
      )}

      <div className="mt-4">
        <LineSeries
          data={points.map((point) => ({ date: point.date, value: point.value }))}
          unit="%p"
          zeroLine
          negativeShade
          color="#58A6FF"
        />
      </div>
    </Card>
  );
}

function RiskSection({ risk, loading }: { risk: RiskIndicators | null; loading: boolean }) {
  if (loading && !risk) {
    return (
      <Card title="⚡ 신용 리스크, 은행권 및 시장 변동성">
        <Loading />
      </Card>
    );
  }
  if (!risk) {
    return (
      <Card title="⚡ 신용 리스크, 은행권 및 시장 변동성">
        <EmptyState message="리스크 지표를 불러오지 못했습니다." />
      </Card>
    );
  }

  const entries: { key: keyof RiskIndicators; label: string; unit: string; digits: number }[] = [
    { key: "vix", label: "CBOE VIX (주식 변동성)", unit: "", digits: 2 },
    { key: "move", label: "MOVE (채권 변동성)", unit: "", digits: 2 },
    { key: "hyOas", label: "하이일드 스프레드 (HY OAS)", unit: "%p", digits: 2 },
    { key: "cpSpread", label: "3M 금융 CP 스프레드", unit: "%p", digits: 2 },
    { key: "stlfsi", label: "세인트루이스 연준 금융스트레스", unit: "pt", digits: 2 },
  ];

  return (
    <Card
      title="⚡ 신용 리스크, 은행권 및 시장 변동성"
      subtitle="주식·채권 변동성, 기업 부도 위험, 단기 자금경색, 종합 금융스트레스"
    >
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        {entries.map((entry) => {
          const item: RiskEntry = risk[entry.key];
          return (
            <Metric
              key={entry.key}
              label={entry.label}
              value={
                item?.available
                  ? `${formatNumber(item.value ?? null, entry.digits)}${entry.unit}`
                  : "수집 실패"
              }
              delta={item?.delta}
              deltaText={
                item?.delta === null || item?.delta === undefined
                  ? EMPTY
                  : `${formatSigned(item.delta, entry.digits)}${entry.unit}`
              }
              tone={item?.available ? undefined : "text-muted"}
              caption={item?.asOf ? `기준일 ${item.asOf}` : undefined}
              note={
                item?.isProxy ? (
                  <span className="text-warn">
                    ⚠️ 실제 지표가 아닙니다 — {item.sourceLabel}
                  </span>
                ) : undefined
              }
            />
          );
        })}
      </div>

      <div className="mt-5">
        <h3 className="mb-2 text-sm font-semibold text-bright">
          📖 신용·은행권·변동성 핵심 해석 기준표
        </h3>
        <Table
          rows={RISK_TABLE}
          rowKey={(row) => row.name}
          columns={[
            { key: "name", header: "지표 (지연 수준)", render: (row) => row.name },
            { key: "normal", header: "정상 / 안정 범위", render: (row) => row.normal },
            { key: "danger", header: "위험 / 발작 임계치", render: (row) => row.danger },
            { key: "note", header: "성격 및 핵심 해석", render: (row) => row.note },
          ]}
        />
      </div>
    </Card>
  );
}

function AdvancedSection({
  data,
  loading,
}: {
  data: AdvancedIndicators | null;
  loading: boolean;
}) {
  const [selected, setSelected] = useState("T10Y3M");
  const series = useApi<{ available: boolean; points: { date: string; value: number }[] }>(
    `/api/macro/fred/${selected}?years=10`,
  );

  if (loading && !data) {
    return (
      <Card title="🧭 심화 매크로 지표">
        <Loading />
      </Card>
    );
  }

  const entries = data ? data.order.map((id) => data.latest[id]).filter(Boolean) : [];

  return (
    <Card
      title="🧭 심화 매크로 지표"
      subtitle="명목금리·하이일드만으로는 보이지 않는 구조를 메우는 5종 (모두 FRED 공식 시계열)"
    >
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        {entries.map((entry) => (
          <Metric
            key={entry.id}
            label={entry.label}
            value={
              entry.available
                ? `${formatNumber(entry.value ?? null, entry.digits)}${entry.unit}`
                : "수집 실패"
            }
            delta={entry.delta}
            deltaText={
              entry.delta === null || entry.delta === undefined
                ? EMPTY
                : formatSigned(entry.delta, entry.digits)
            }
            tone={entry.available ? statusColor(entry.color) : "text-muted"}
            caption={
              entry.available ? (
                <span className="flex flex-col gap-0.5">
                  {entry.status && <span>상태: {entry.status}</span>}
                  {entry.percentile !== null && entry.percentile !== undefined && (
                    <span>표본 백분위 {formatNumber(entry.percentile, 1)}%</span>
                  )}
                  {entry.asOf && <span>기준일 {entry.asOf}</span>}
                </span>
              ) : undefined
            }
            note={entry.note}
          />
        ))}
      </div>

      {data?.derived?.decomposition && (
        <p className="mt-4 rounded border border-border bg-canvas px-3 py-2 text-xs text-muted">
          {data.derived.decomposition}
          <span className="ml-2">
            (세 값의 기준 시점이 다르면 오차가 생기므로 참고용입니다.)
          </span>
        </p>
      )}

      <div className="mt-5">
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <Select
            label="추이 차트 지표"
            value={selected}
            onChange={setSelected}
            options={entries.map((entry) => ({ value: entry.id, label: entry.label }))}
          />
          <p className="text-xs text-muted">
            {data?.latest[selected]?.why} · 출처: {data?.latest[selected]?.source}
          </p>
        </div>
        <LineSeries
          data={(series.data?.points ?? []).map((point) => ({
            date: point.date,
            value: point.value,
          }))}
          unit={data?.latest[selected]?.unit ?? ""}
          zeroLine={selected === "T10Y3M" || selected === "NFCI"}
          negativeShade={selected === "T10Y3M"}
        />
      </div>
    </Card>
  );
}

const SINGLE_TICKERS = [
  { value: "^VIX", label: "CBOE VIX" },
  { value: "^MOVE", label: "MOVE (추정치)" },
  { value: "^GSPC", label: "S&P 500" },
  { value: "^NDX", label: "나스닥 100" },
  { value: "^KS11", label: "코스피" },
  { value: "^N225", label: "닛케이 225" },
  { value: "KRW=X", label: "원/달러" },
  { value: "DX-Y.NYB", label: "달러 인덱스" },
  { value: "CL=F", label: "WTI 원유" },
  { value: "GC=F", label: "금 선물" },
];

const PERIODS = ["1mo", "3mo", "6mo", "1y", "2y", "5y"];

function SingleChartSection() {
  const [symbol, setSymbol] = useState("^VIX");
  const [period, setPeriod] = useState("1y");

  const { data, loading, error } = useApi<{
    available: boolean;
    isProxy?: boolean;
    sourceLabel?: string | null;
    points: { date: string; close: number | null; value?: number | null }[];
  }>(`/api/macro/ticker?symbol=${encodeURIComponent(symbol)}&period=${period}`);

  return (
    <Card title="📈 지표별 기간별 단독 차트">
      <div className="mb-4 flex flex-wrap gap-3">
        <Select label="지표" value={symbol} onChange={setSymbol} options={SINGLE_TICKERS} />
        <Select
          label="조회 기간"
          value={period}
          onChange={setPeriod}
          options={PERIODS.map((value) => ({ value, label: value }))}
        />
      </div>

      {data?.isProxy && (
        <div className="mb-3">
          <Banner tone="warn">⚠️ {data.sourceLabel}</Banner>
        </div>
      )}

      {loading && <Loading />}
      {error && <ErrorState message={error} />}
      {!loading && !error && (
        <LineSeries
          data={(data?.points ?? []).map((point) => ({
            date: point.date.slice(0, 10),
            value: point.close ?? point.value ?? null,
          }))}
        />
      )}
    </Card>
  );
}

function ScrapedSection() {
  const { data, loading } = useApi<{
    available: boolean;
    updatedAt?: string;
    items: {
      key: string;
      name: string;
      provider: string;
      unit: string;
      status: string;
      price: number | null;
      previousClose: number | null;
      changePct: number | null;
      error: string | null;
    }[];
  }>("/api/macro/scraped", 120_000);

  return (
    <Card
      title="🔎 비공식 스크래핑 시세 비교"
      subtitle="TradingView·Yahoo 공개 엔드포인트에서 받은 참고 시세입니다. 공식 확정치가 아닙니다."
      actions={data?.updatedAt ? <SourceBadge>{data.updatedAt}</SourceBadge> : undefined}
    >
      {loading && !data && <Loading />}
      {data && (
        <Table
          rows={data.items ?? []}
          rowKey={(row) => row.key}
          columns={[
            { key: "name", header: "항목", render: (row) => row.name },
            { key: "provider", header: "출처", render: (row) => <SourceBadge>{row.provider}</SourceBadge> },
            {
              key: "price",
              header: "현재가",
              align: "right",
              render: (row) =>
                row.status === "ok" ? `${formatNumber(row.price, 3)} ${row.unit}` : "수집 실패",
            },
            {
              key: "prev",
              header: "전일 종가",
              align: "right",
              render: (row) => (row.previousClose === null ? EMPTY : formatNumber(row.previousClose, 3)),
            },
            {
              key: "changePct",
              header: "등락률",
              align: "right",
              render: (row) => (
                <span className={deltaColor(row.changePct)}>{formatPercent(row.changePct)}</span>
              ),
            },
            {
              key: "error",
              header: "비고",
              render: (row) => <span className="text-xs text-muted">{row.error ?? ""}</span>,
            },
          ]}
        />
      )}
    </Card>
  );
}
