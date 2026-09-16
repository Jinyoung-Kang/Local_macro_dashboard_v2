/**
 * src/lib/format.ts
 * 숫자·날짜 표시 헬퍼.
 *
 * <b>핵심 규칙</b> — 값이 없으면 "—"로 표시합니다. 절대 0으로 대체하지
 * 않습니다. 화면에서 "0.00%"는 '데이터 없음'이 아니라 '보합'으로 읽히고,
 * 구버전에서 실제로 잘못된 판단을 유발했던 부분입니다.
 */

export const EMPTY = "—";

export function formatNumber(
  value: number | null | undefined,
  digits = 2,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  return value.toLocaleString("ko-KR", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function formatSigned(
  value: number | null | undefined,
  digits = 2,
  suffix = "",
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  const sign = value > 0 ? "+" : "";
  return `${sign}${formatNumber(value, digits)}${suffix}`;
}

export function formatPercent(
  value: number | null | undefined,
  digits = 2,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  return `${formatSigned(value, digits)}%`;
}

/** 한국 시장 관행: 상승 = 빨강, 하락 = 파랑. 값이 없으면 중립색. */
export function deltaColor(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "text-muted";
  }
  if (value > 0) {
    return "text-up";
  }
  if (value < 0) {
    return "text-down";
  }
  return "text-body";
}

export function formatCurrency(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  if (Math.abs(value) >= 1e9) {
    return `$${(value / 1e9).toFixed(2)}B`;
  }
  if (Math.abs(value) >= 1e6) {
    return `$${(value / 1e6).toFixed(2)}M`;
  }
  return `$${value.toLocaleString("ko-KR", { maximumFractionDigits: 0 })}`;
}

/** 수집 후 경과 시간을 사람이 읽는 문장으로. */
export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return EMPTY;
  }
  if (seconds < 60) {
    return `${Math.round(seconds)}초 전`;
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)}분 전`;
  }
  if (seconds < 86400) {
    return `${Math.round(seconds / 3600)}시간 전`;
  }
  return `${Math.round(seconds / 86400)}일 전`;
}

export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return EMPTY;
  }
  return value.slice(0, 10);
}

export function formatDateTimeKst(value: string | null | undefined): string {
  if (!value) {
    return EMPTY;
  }
  try {
    return new Intl.DateTimeFormat("ko-KR", {
      timeZone: "Asia/Seoul",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    }).format(new Date(value));
  } catch {
    return value;
  }
}

/** 해석 색상(백엔드가 내려주는 green/blue/orange/red)을 클래스명으로. */
export function statusColor(color: string | null | undefined): string {
  switch (color) {
    case "green":
      return "text-ok";
    case "blue":
      return "text-accent";
    case "orange":
      return "text-warn";
    case "red":
      return "text-danger";
    default:
      return "text-body";
  }
}
