"use client";

import { useId } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { EMPTY, formatNumber } from "@/lib/format";

const AXIS = { stroke: "#8B949E", fontSize: 11 };
const GRID = "#30363D";

/**
 * 시계열 색 (다크 표면 #161B22 기준).
 *
 * dataviz 검증기(scripts/validate_palette.js)를 통과한 조합입니다.
 * 이전 조합(#58A6FF·#D29922·#F85149)은 적록색약에서 노랑↔빨강 ΔE가 5.8로
 * 구분 한계(6) 아래였습니다 — 화면에서도 두 선이 겹쳐 보였습니다.
 */
export const SERIES_COLORS = {
  blue: "#3987e5",
  orange: "#d95926",
  green: "#199e70",
} as const;

/**
 * Y축 표시 범위.
 *
 * <b>왜 필요한가</b> — Recharts는 면적 차트의 Y축을 0부터 그립니다. 순유동성처럼
 * 값이 5.85~6.0조 달러 사이에서 움직이는 계열에 0~8조 축을 쓰면, 정작 읽어야 할
 * 변동이 축 꼭대기 얇은 띠에 눌려 직선처럼 보입니다.
 *
 * 0이 의미를 갖는 계열(스프레드처럼 부호가 중요한 값)만 0을 포함시키고,
 * 나머지는 데이터 범위에 맞춰 여백만 둡니다.
 */
function valueDomain(
  data: { value: number | null }[],
  includeZero: boolean,
): [number | "auto", number | "auto"] {
  const values = data
    .map((point) => point.value)
    .filter((value): value is number => value !== null && Number.isFinite(value));

  if (values.length === 0) {
    return ["auto", "auto"];
  }

  let min = Math.min(...values);
  let max = Math.max(...values);
  if (includeZero) {
    min = Math.min(min, 0);
    max = Math.max(max, 0);
  }

  // 위아래로 8%씩 숨 쉴 공간. 선이 축에 붙어 잘린 것처럼 보이지 않게 합니다.
  const span = max - min;
  const pad = span === 0 ? Math.abs(max) * 0.05 || 1 : span * 0.08;

  // 값이 모두 0 이상인 계열은 축을 0 아래로 내리지 않습니다. 역레포처럼
  // 음수가 될 수 없는 양에 "-0.11T" 눈금이 찍히면 있을 수 없는 값을
  // 있을 수 있는 것처럼 보여 주게 됩니다.
  const lower = min >= 0 ? Math.max(0, min - pad) : min - pad;
  return [lower, max + pad];
}

/**
 * 축 라벨 길이를 눈대중으로 잽니다.
 *
 * 한글·한자는 폭이 라틴 문자의 약 두 배입니다. 글자 수만 세면
 * "KODEX 레버리지"와 "TIGER 미국필라델피아반도체나스닥"을 같게 보게 됩니다.
 */
function visualWidth(text: string): number {
  let width = 0;
  for (const char of text) {
    width += /[가-힣ㄱ-ㅎㅏ-ㅣ一-鿿ぁ-ヿ]/.test(char) ? 2 : 1;
  }
  return width;
}

function truncateToWidth(text: string, maxWidth: number): string {
  if (visualWidth(text) <= maxWidth) {
    return text;
  }
  let out = "";
  let width = 0;
  for (const char of text) {
    const next = width + (/[가-힣ㄱ-ㅎㅏ-ㅣ一-鿿ぁ-ヿ]/.test(char) ? 2 : 1);
    if (next > maxWidth - 1) {
      break;
    }
    out += char;
    width = next;
  }
  return out + "…";
}

/**
 * 한 줄로만 그리는 세로축 라벨.
 *
 * Recharts 기본 렌더러는 폭을 넘는 라벨을 여러 줄로 접습니다. 막대가 15개면
 * 한 칸이 24px 남짓인데 두 줄은 26px이 넘어서 **위아래 라벨이 서로 겹칩니다**
 * (실제로 수급 레이더 화면에서 종목명이 뭉개져 읽을 수 없었습니다).
 * 잘라서 한 줄로 그리고, 전체 이름은 툴팁이 보여 줍니다.
 */
function CategoryTick({
  x,
  y,
  payload,
  maxWidth,
}: {
  x?: number;
  y?: number;
  payload?: { value?: string | number };
  maxWidth: number;
}) {
  const label = String(payload?.value ?? "");
  return (
    <text
      x={x}
      y={y}
      dy={4}
      textAnchor="end"
      fill={AXIS.stroke}
      fontSize={AXIS.fontSize}
    >
      <title>{label}</title>
      {truncateToWidth(label, maxWidth)}
    </text>
  );
}

function tooltipStyle() {
  return {
    contentStyle: {
      backgroundColor: "#161B22",
      border: "1px solid #30363D",
      borderRadius: 8,
      fontSize: 12,
      color: "#C9D1D9",
    },
    labelStyle: { color: "#8B949E" },
  };
}

export type Point = { date: string; value: number | null };

/** 단일 시계열 라인 차트. */
export function LineSeries({
  data,
  color = "#58A6FF",
  height = 260,
  unit = "",
  zeroLine = false,
  negativeShade = false,
}: {
  data: Point[];
  color?: string;
  height?: number;
  unit?: string;
  /** 0선을 그립니다 (스프레드·지수처럼 부호가 의미 있는 값). */
  zeroLine?: boolean;
  /**
   * 예전에는 음수 구간을 붉게 칠했습니다. 지금은 0선을 실선으로 그려
   * 역전 여부를 보여 주고, 판정 문구는 카드 상단 배너가 맡습니다
   * (색만으로 의미를 전달하지 않기 위해서입니다).
   */
  negativeShade?: boolean;
}) {
  // ⚠️ 그라디언트 id는 문서 전체에서 유일해야 합니다. 예전에는 "fill"로
  // 고정돼 있어서, 한 페이지에 차트가 여러 개면(매크로 화면은 3개) 전부
  // 첫 번째 차트의 색을 쓰는 상태였습니다.
  //
  // 훅은 조기 반환보다 **위**에 있어야 합니다 — 렌더마다 호출 순서가 같아야
  // 하기 때문입니다.
  const gradientId = `line-fill-${useId().replace(/:/g, "")}`;

  if (data.length === 0) {
    return <div className="py-10 text-center text-sm text-muted">표시할 시계열이 없습니다.</div>;
  }

  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor={color} stopOpacity={0.3} />
            <stop offset="95%" stopColor={color} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        {/* 점선 격자는 '임계선'처럼 읽힙니다. 격자는 실선 헤어라인으로. */}
        <CartesianGrid stroke={GRID} vertical={false} />
        <XAxis dataKey="date" tick={AXIS} minTickGap={40} tickLine={false} />
        <YAxis
          tick={AXIS}
          tickLine={false}
          width={64}
          domain={valueDomain(data, zeroLine)}
          tickFormatter={(value: number) => `${formatNumber(value, 2)}${unit}`}
        />
        <Tooltip
          {...tooltipStyle()}
          cursor={{ stroke: "#8B949E", strokeWidth: 1 }}
          formatter={(value: number) => [`${formatNumber(value, 3)}${unit}`, "값"]}
        />
        {zeroLine && <ReferenceLine y={0} stroke="#8B949E" strokeWidth={1} />}
        <Area
          type="monotone"
          dataKey="value"
          stroke={color}
          strokeWidth={2}
          fill={`url(#${gradientId})`}
          connectNulls={false}
          dot={false}
          activeDot={{ r: 4, strokeWidth: 2, stroke: "#161B22" }}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

/** 여러 시계열 비교. */
export function MultiLineSeries({
  data,
  series,
  height = 280,
  unit = "",
}: {
  data: Record<string, unknown>[];
  series: { key: string; name: string; color: string }[];
  height?: number;
  unit?: string;
}) {
  if (data.length === 0) {
    return <div className="py-10 text-center text-sm text-muted">표시할 시계열이 없습니다.</div>;
  }

  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
        <CartesianGrid stroke={GRID} vertical={false} />
        <XAxis dataKey="date" tick={AXIS} minTickGap={40} tickLine={false} />
        <YAxis
          tick={AXIS}
          tickLine={false}
          width={64}
          tickFormatter={(value: number) => `${formatNumber(value, 1)}${unit}`}
        />
        <Tooltip
          {...tooltipStyle()}
          formatter={(value: number, name: string) => [
            value === null || value === undefined ? EMPTY : `${formatNumber(value, 2)}${unit}`,
            name,
          ]}
        />
        <Legend wrapperStyle={{ fontSize: 11, color: "#8B949E" }} />
        {series.map((entry) => (
          <Line
            key={entry.key}
            type="monotone"
            dataKey={entry.key}
            name={entry.name}
            stroke={entry.color}
            strokeWidth={1.8}
            dot={false}
            connectNulls={false}
          />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}

/** 가로 막대 (수급 순위·수익률 비교). */
export function HorizontalBars({
  data,
  height = 360,
  unit = "",
}: {
  data: { name: string; value: number }[];
  height?: number;
  unit?: string;
}) {
  if (data.length === 0) {
    return <div className="py-10 text-center text-sm text-muted">표시할 데이터가 없습니다.</div>;
  }

  // 항목이 많으면 높이를 늘립니다. 고정 높이로 30개를 그리면 한 칸이 12px이라
  // 막대도 라벨도 읽을 수 없습니다.
  const chartHeight = Math.max(height, data.length * 26 + 48);

  return (
    <ResponsiveContainer width="100%" height={chartHeight}>
      <BarChart
        data={data}
        layout="vertical"
        margin={{ top: 8, right: 16, bottom: 0, left: 8 }}
      >
        <CartesianGrid stroke={GRID} horizontal={false} />
        <XAxis
          type="number"
          tick={AXIS}
          tickLine={false}
          tickFormatter={(value: number) => `${formatNumber(value, 0)}${unit}`}
        />
        <YAxis
          type="category"
          dataKey="name"
          width={150}
          tickLine={false}
          interval={0}
          tick={(props) => <CategoryTick {...props} maxWidth={24} />}
        />
        <Tooltip
          {...tooltipStyle()}
          formatter={(value: number) => [`${formatNumber(value, 2)}${unit}`, "값"]}
        />
        <ReferenceLine x={0} stroke="#8B949E" />
        {/* 한국 관행: 양수(순매수·상승) 빨강, 음수 파랑 */}
        <Bar dataKey="value" radius={[0, 4, 4, 0]}>
          {data.map((entry) => (
            <Cell key={entry.name} fill={entry.value >= 0 ? "#F85149" : "#4493F8"} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}

/** 순매수/순매도를 색으로 구분하는 막대 차트. */
export function SignedBars({
  data,
  height = 320,
  unit = "",
}: {
  data: { name: string; value: number }[];
  height?: number;
  unit?: string;
}) {
  if (data.length === 0) {
    return <div className="py-10 text-center text-sm text-muted">표시할 데이터가 없습니다.</div>;
  }

  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
        <CartesianGrid stroke={GRID} vertical={false} />
        <XAxis dataKey="name" tick={AXIS} tickLine={false} interval={0} angle={-25} height={70} textAnchor="end" />
        <YAxis
          tick={AXIS}
          tickLine={false}
          width={64}
          tickFormatter={(value: number) => `${formatNumber(value, 0)}${unit}`}
        />
        <Tooltip
          {...tooltipStyle()}
          formatter={(value: number) => [`${formatNumber(value, 0)}${unit}`, "순매수"]}
        />
        <ReferenceLine y={0} stroke="#8B949E" />
        <Bar dataKey="value" radius={[4, 4, 0, 0]}>
          {data.map((entry) => (
            <Cell key={entry.name} fill={entry.value >= 0 ? "#F85149" : "#4493F8"} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
