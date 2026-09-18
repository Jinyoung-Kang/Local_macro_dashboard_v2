/**
 * src/lib/types.ts
 * 백엔드 응답 계약.
 *
 * 값이 없을 수 있는 필드는 전부 `| null`입니다. 프런트는 그 null을 그대로
 * "—"로 표시하고, 절대 0으로 바꾸지 않습니다.
 */

export interface MacroItem {
  key: string;
  name: string;
  note?: string | null;
  ticker?: string | null;
  status: "ok" | "single" | "fail";
  price?: number | null;
  priceStr?: string | null;
  delta?: number | null;
  pct?: number | null;
  deltaStr?: string | null;
  prevStr?: string | null;
  prevValue?: number | null;
  prevSource?: string | null;
  lastTs?: string | null;
  source?: string | null;
  isReference?: boolean;
}

export interface MacroCategory {
  id: string;
  title: string;
  note?: string | null;
  items: MacroItem[];
}

export interface SpreadBlock {
  longId: string;
  shortId: string;
  points: { date: string; value: number }[];
  latest: number | null;
  previous: number | null;
  /** 같은 쌍을 스크래핑 시세로 계산한 "지금" 값. 공식 확정치와 함께 보여 줍니다. */
  scraped?: ScrapedSpread;
}

export interface MacroOverview {
  available: boolean;
  readMode: string;
  message?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  categories: MacroCategory[];
  rates?: Record<string, { current: number | null; previous: number | null }>;
  spreads?: {
    realtime: ScrapedSpread;
    official10y2y: SpreadBlock;
    official30y2y: SpreadBlock;
  };
}

/** 스크래핑 수익률로 계산한 "지금" 스프레드 (공식 확정치와 성격이 다릅니다). */
export interface ScrapedSpread {
  /** 만기 키(us02y·us10y·us30y). 값 자체는 longValue/shortValue에만 있습니다. */
  longKey?: string;
  shortKey?: string;
  longValue: number | null;
  shortValue: number | null;
  spread: number | null;
  previousSpread: number | null;
  delta: number | null;
}

export interface RiskEntry {
  available: boolean;
  label?: string;
  unit?: string;
  value?: number | null;
  previous?: number | null;
  delta?: number | null;
  pct?: number | null;
  asOf?: string | null;
  percentile?: number | null;
  isProxy?: boolean;
  sourceLabel?: string | null;
  points?: { date: string; value: number }[];
}

export interface RiskIndicators {
  vix: RiskEntry;
  move: RiskEntry;
  hyOas: RiskEntry;
  cpSpread: RiskEntry;
  stlfsi: RiskEntry;
}

export interface AdvancedEntry {
  id: string;
  label: string;
  unit: string;
  digits: number;
  group: string;
  why: string;
  source: string;
  available: boolean;
  value?: number | null;
  prev?: number | null;
  delta?: number | null;
  asOf?: string | null;
  percentile?: number | null;
  status?: string;
  color?: string;
  note?: string;
}

export interface AdvancedIndicators {
  order: string[];
  latest: Record<string, AdvancedEntry>;
  derived: { impliedNominal10y?: number; decomposition?: string };
}

export interface LiquidityRow {
  date: string;
  walcl: number;
  wtregen: number;
  rrpM: number;
  rrpB: number;
  netLiquidityM: number;
  netLiquidityT: number;
  walclT: number;
  wtregenB: number;
}

export interface LiquidityResponse {
  available: boolean;
  isEstimated?: boolean;
  message?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  rows: LiquidityRow[];
  latest?: {
    date?: string;
    netLiquidityT: number | null;
    previousT: number | null;
    deltaT: number | null;
    pct: number | null;
    walclT?: number | null;
    tgaB?: number | null;
    rrpB?: number | null;
  };
  momentum?: { change4w: number | null; change12w: number | null };
}

export interface SectorRow {
  ticker: string;
  name: string;
  kind: string;
  price: number | null;
  returns: Record<string, number | null>;
  alpha?: Record<string, number | null>;
  ranks?: Record<string, number>;
}

export interface SectorResponse {
  available: boolean;
  message?: string;
  period: string;
  periods: string[];
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  benchmark: string;
  benchmarkReturns: Record<string, number | null>;
  sectors: SectorRow[];
  assetClasses: SectorRow[];
}

export interface Holding {
  name: string;
  cusip: string;
  class: string;
  value: number | null;
  shares: number | null;
  weight: number | null;
  weightDiff: number | null;
  sharesDiff: number | null;
  action: string;
}

export interface PortfolioResponse {
  available: boolean;
  message?: string;
  error?: string | null;
  cik: string;
  institution?: { name?: string; desc?: string };
  collectedAtKst?: string;
  ageSeconds?: number;
  quarters: { filingDate: string; reportDate: string; totalValue: number; holdingCount: number }[];
  latest?: { reportDate: string; filingDate: string; totalValue: number | null };
  holdings: Holding[];
  weightHistory?: { dates: string[]; series: Record<string, number[]> };
}

export interface ConsensusRow {
  name: string;
  cusip: string;
  holders: string[];
  actions: string[];
  holderCount: number;
  totalValue: number;
  avgWeight: number;
  maxWeight: number;
  buyCount: number;
  sellCount: number;
}

export interface ConsensusResponse {
  available: boolean;
  participants: string[];
  participantCount: number;
  availableDates: string[];
  reportDate: string | null;
  minHolders: number;
  rows: ConsensusRow[];
}

export interface CotSummary {
  asset: string;
  available: boolean;
  category?: string;
  error?: string | null;
  date?: string;
  ncNet?: number | null;
  commNet?: number | null;
  nrNet?: number | null;
  change1w?: number | null;
  change4w?: number | null;
  change13w?: number | null;
  percentile?: number | null;
  ageDays?: number | null;
}

export interface CotAssetResponse {
  asset: string;
  available: boolean;
  message?: string;
  code?: string;
  category?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  rows: { date: string; ncNet: number; commNet: number; nrNet: number }[];
  summary?: CotSummary;
}

export interface KrxRow {
  date: string;
  futuresClose: number | null;
  changePct: number | null;
  changePctReported: number | null;
  volume: number | null;
  openInterest: number | null;
  oiChange: number | null;
  theoryPrice: number | null;
  marketBasis: number | null;
  contractName: string;
  marketPhase: string;
  cotOiIndex: number | null;
}

export interface KrxFuturesResponse {
  available: boolean;
  message?: string;
  isEstimated?: boolean;
  estimateNotice?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  rows: KrxRow[];
  latest?: {
    date?: string;
    futuresClose: number | null;
    changePct: number | null;
    changePctReported: number | null;
    volume: number | null;
    openInterest: number | null;
    oiChange: number | null;
    marketBasis: number | null;
    theoryPrice: number | null;
    contractName?: string;
    marketPhase?: string;
    cotOiIndex: number | null;
    basisState?: string;
    basisNote?: string;
    oiChange5dAvg: number | null;
  };
}

export interface InvestorTrendResponse {
  available: boolean;
  message?: string;
  collectedAtKst?: string;
  ageSeconds?: number;
  dataDate?: string | null;
  measure?: string;
  unit?: string;
  source?: string;
  rows: { investor: string; netToday: number; net5d: number; net20d: number; stance: string }[];
}

export interface RadarRow {
  rank: number;
  code: string;
  name: string;
  price: number | null;
  changePct: number | null;
  netAmountEok: number | null;
  source?: string;
  collectedAt?: string;
}

export interface RadarResponse {
  available: boolean;
  message?: string;
  /** 폴백 체인이 모두 실패했을 때, 소스별로 왜 못 줬는지. */
  reasons?: string[];
  warning?: string;
  market: string;
  investor: string;
  tradeType: string;
  intervalType: string;
  readMode: string;
  source?: string | null;
  sourceKind?: string | null;
  isHistorical?: boolean;
  historyDate?: string | null;
  collectedAtKst?: string;
  ageSeconds?: number;
  stale?: boolean;
  rows: RadarRow[];
}

export interface TaskSummary {
  task: string;
  speed: string | null;
  status: "ok" | "empty" | "error";
  startedAt: string | null;
  durationMs: number | null;
  detail: string | null;
}

export interface StatusResponse {
  readMode: string;
  collectorReachable: boolean;
  message?: string;
  keys?: Record<string, boolean>;
  intervals?: Record<string, number>;
  missingDatasets?: { name: string; label: string }[];
  lastRun?: {
    id?: number;
    startedAt?: string;
    finishedAt?: string;
    status?: string;
    okCount?: number;
    failCount?: number;
    detail?: string;
    pid?: number;
    groupName?: string;
  } | null;
  lastRunStatus?: string;
  taskSummary?: TaskSummary[];
  timeseriesRows?: number;
  observationRows?: number;
  snapshots?: {
    name: string;
    status: string;
    error: string | null;
    collectedAt: string | null;
    ageSeconds?: number;
    stale?: boolean;
  }[];
  radarHistoryDates?: string[];
}

export interface VerificationResult {
  name: string;
  verdict: "match" | "mismatch" | "skipped" | "error";
  label: string;
  tolerancePct: number | null;
  diffPct: number | null;
  note: string | null;
  readings: { source: string; ok: boolean; value: number | null; detail: string | null }[];
}

export interface VerificationResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  headline?: string;
  matchCount?: number;
  mismatchCount?: number;
  errorCount?: number;
  skippedCount?: number;
  keys?: { krx: boolean; kis: boolean };
  results?: VerificationResult[];
  exitCode?: number;
}

export interface AiEngine {
  id: string;
  label: string;
  provider: string;
  model: string | null;
  description: string;
  /** 예상 응답 속도 — 추론형 모델이 왜 느린지 고르기 전에 알려 줍니다. */
  speedHint?: string;
  available: boolean;
}

export interface AiEngines {
  engines: AiEngine[];
  enabled: boolean;
  /** 엔진을 직접 골랐을 때의 대기 한도(초). */
  timeoutSeconds?: number;
  /** 자동 탐색에서 엔진 하나를 기다리는 한도(초). */
  autoAttemptSeconds?: number;
  /** 자동 탐색 전체 시간 예산(초). */
  autoBudgetSeconds?: number;
}

/** 📋 전체 대시보드 원본 데이터 (AI 분석 없음). */
export interface SnapshotText {
  text: string;
  generatedAtKst: string;
  chars: number;
  lineCount: number;
  sections: string[];
}

export interface AiResponse {
  status: boolean;
  provider?: string;
  model?: string;
  response?: string;
  error?: string | null;
  latencyMs?: number;
  pipelineStep?: string;
  failoverPath?: string[];
  translationInfo?: string;
  originalResponse?: string;
  reportType?: string;
}

export interface DiagnosticsResponse {
  available: boolean;
  message?: string;
  checkedAt?: string;
  sources?: Record<
    string,
    { ok: boolean; stage: string; message: string; sample?: unknown }
  >;
}

// ------------------------------------------------- 🔗 상관관계 · 🧭 국면
export interface SeriesRef {
  id: string;
  label: string;
  group: string;
  unit: string;
  source: string;
}

export interface CorrelationResponse {
  available: boolean;
  message?: string;
  x?: SeriesRef;
  y?: SeriesRef;
  mode: "change" | "level";
  window: number;
  /** 전체 구간 상관계수. 계산할 수 없으면 null입니다(0이 아닙니다). */
  overall: number | null;
  samples: number;
  firstDate?: string | null;
  lastDate?: string | null;
  rolling?: { date: string; value: number | null }[];
  scatter?: { date: string; x: number; y: number }[];
  notes?: string[];
}

export interface RegimeSignal {
  id: string;
  label: string;
  value: number | null;
  unit: string;
  state: string;
  reading: string;
  healthy: boolean;
}

export interface RegimeVerdict {
  code: "EXPANSION" | "LATE" | "RECOVERY" | "CONTRACTION" | "UNKNOWN";
  label: string;
  summary: string;
  growthAxis: string;
  liquidityAxis: string;
  signals: RegimeSignal[];
  missing: string[];
}

export interface RegimeResponse {
  available: boolean;
  asOf?: string | null;
  verdict?: RegimeVerdict;
  timeline?: { date: string; code: string; label: string }[];
  episodes?: { code: string; label: string; start: string; end: string; weeks: number }[];
  note?: string;
}

// ----------------------------------------------- 📉 COT 극단값 백테스트
export interface CotExtremeSummary {
  count: number;
  mean: number | null;
  median: number | null;
  winRate: number | null;
  best: number | null;
  worst: number | null;
}

export interface CotExtremeSide {
  side: string;
  rule: string;
  h4: CotExtremeSummary;
  h13: CotExtremeSummary;
  baseline4: CotExtremeSummary;
  baseline13: CotExtremeSummary;
}

export interface CotExtremesResponse {
  available: boolean;
  message?: string;
  asset: string;
  percentile: number;
  lookbackWeeks?: number;
  priceProxy?: string | null;
  priceProxyLabel?: string | null;
  proxyNotice?: string;
  priceFrom?: string;
  priceTo?: string;
  sides?: CotExtremeSide[];
  recentEvents?: {
    date: string;
    side: string;
    net: number;
    percentile: number;
    return4w: number | null;
    return13w: number | null;
  }[];
}

// ------------------------------------------------ 🆕 13F 공통 신규 매수
export interface NewBuysResponse {
  available: boolean;
  participants: string[];
  participantCount: number;
  availableDates: string[];
  reportDate?: string | null;
  minHolders: number;
  note?: string;
  rows: {
    name: string;
    cusip?: string;
    buyers: string[];
    buyerCount: number;
    totalValue: number;
    avgWeight: number;
    reportDate: string;
  }[];
}

/** 💱 원/달러 (달러 금액을 원화로 병기할 때). */
export interface UsdKrwResponse {
  available: boolean;
  /** 값이 없으면 available=false이고 rate는 없습니다(기본 환율을 지어내지 않습니다). */
  rate?: number;
  name?: string;
  lastTs?: string | null;
  source?: string | null;
  collectedAtKst?: string;
  message?: string;
}
