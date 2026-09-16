"use client";

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
  /** 음수 구간을 붉게 강조합니다 (금리 역전 구간 표시). */
  negativeShade?: boolean;
}) {
  if (data.length === 0) {
    return <div className="py-10 text-center text-sm text-muted">표시할 시계열이 없습니다.</div>;
  }

  return (
    <ResponsiveContainer width="100%" height={height}>
      <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id="fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor={color} stopOpacity={0.35} />
            <stop offset="95%" stopColor={color} stopOpacity={0.02} />
          </linearGradient>
          {negativeShade && (
            <linearGradient id="negative" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#F85149" stopOpacity={0.25} />
              <stop offset="100%" stopColor="#F85149" stopOpacity={0.05} />
            </linearGradient>
          )}
        </defs>
        <CartesianGrid stroke={GRID} strokeDasharray="3 3" vertical={false} />
        <XAxis dataKey="date" tick={AXIS} minTickGap={40} tickLine={false} />
        <YAxis
          tick={AXIS}
          tickLine={false}
          width={56}
          tickFormatter={(value: number) => `${formatNumber(value, 2)}${unit}`}
        />
        <Tooltip
          {...tooltipStyle()}
          formatter={(value: number) => [`${formatNumber(value, 3)}${unit}`, "값"]}
        />
        {zeroLine && <ReferenceLine y={0} stroke="#F85149" strokeDasharray="4 4" />}
        <Area
          type="monotone"
          dataKey="value"
          stroke={color}
          strokeWidth={2}
          fill="url(#fill)"
          connectNulls={false}
          dot={false}
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
        <CartesianGrid stroke={GRID} strokeDasharray="3 3" vertical={false} />
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
        <CartesianGrid stroke={GRID} strokeDasharray="3 3" horizontal={false} />
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
        <CartesianGrid stroke={GRID} strokeDasharray="3 3" vertical={false} />
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
