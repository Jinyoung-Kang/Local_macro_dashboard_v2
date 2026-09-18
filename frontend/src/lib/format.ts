/**
 * src/lib/format.ts
 * 숫자·날짜 표시 헬퍼.
 *
 * <b>핵심 규칙</b> — 값이 없으면 "—"로 표시합니다. 절대 0으로 대체하지
 * 않습니다. 화면에서 "0.00%"는 '데이터 없음'이 아니라 '보합'으로 읽히고,
 * 구버전에서 실제로 잘못된 판단을 유발했던 부분입니다.
 */

export const EMPTY = "—";

/**
 * 표시 자릿수로 반올림한 값.
 *
 * 화면에 쓰는 모든 판단(부호·색)은 **보이는 숫자**를 기준으로 해야 합니다.
 * -0.004를 소수점 2자리로 찍으면 "-0.00"이 나오는데, 이건 읽는 사람에게
 * 아무 뜻도 없는 데다 하락색까지 입습니다. 반올림 결과가 0이면 0입니다.
 */
function roundForDisplay(value: number, digits: number): number {
  const rounded = Number(value.toFixed(digits));
  return Object.is(rounded, -0) ? 0 : rounded;
}

export function formatNumber(
  value: number | null | undefined,
  digits = 2,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  return roundForDisplay(value, digits).toLocaleString("ko-KR", {
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
  // 부호도 반올림 후 값으로 정합니다. 그래야 "+0.00"·"-0.00"이 안 나옵니다.
  const shown = roundForDisplay(value, digits);
  const sign = shown > 0 ? "+" : "";
  return `${sign}${formatNumber(shown, digits)}${suffix}`;
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

/**
 * 한국 시장 관행: 상승 = 빨강, 하락 = 파랑. 값이 없으면 중립색.
 *
 * `digits`는 **그 자리에 함께 찍는 숫자의 자릿수**를 넘겨야 합니다. 색과 숫자가
 * 따로 놀면 "0.00인데 파란색" 같은 화면이 나옵니다.
 */
export function deltaColor(
  value: number | null | undefined,
  digits = 2,
): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "text-muted";
  }
  const shown = roundForDisplay(value, digits);
  if (shown > 0) {
    return "text-up";
  }
  if (shown < 0) {
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

/**
 * 조 달러 단위 변화량.
 *
 * <p>순유동성의 하루 변화는 0.003조 달러처럼 작습니다. 소수 세 자리로 조
 * 단위를 쓰면 "+0.000 조 달러"가 되어 <b>변화가 없는 것처럼</b> 보입니다.
 * 조 단위로 유의미하지 않은 크기는 십억 달러로 바꿔 적습니다(같은 값입니다).
 */
export function formatTrillionDelta(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  if (Math.abs(value) >= 0.01) {
    return `${formatSigned(value, 3)} 조 달러`;
  }
  return `${formatSigned(value * 1000, 1)} 십억 달러`;
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

/**
 * 수집 시각 배지에 넣을 문구.
 *
 * 백엔드가 이미 KST로 찍어 준 문자열("2026-09-17 20:55:59 KST")을 받습니다.
 * 오늘 받은 값이면 시:분:초만, 어제 이전이면 날짜까지 함께 보여 줍니다.
 *
 * 예전에는 "1초 전"처럼 경과 시간만 보여 줬습니다. 그런데 화면을 열어 두고
 * 한참 뒤에 보면 그 문구는 열었을 때 기준이라, 몇 시 값인지 알 수 없었습니다.
 * 절대 시각은 화면이 멈춰 있어도 틀리지 않습니다.
 */
export function formatCollectedAtKst(value: string | null | undefined): string {
  if (!value) {
    return EMPTY;
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}:\d{2}:\d{2})/.exec(value);
  if (!match) {
    // 형식이 다르면 받은 값을 그대로 보여 줍니다 — 시각을 지어내지 않습니다.
    return value;
  }
  const [, year, month, day, time] = match;
  return isTodayInKst(`${year}-${month}-${day}`)
    ? `${time} KST`
    : `${month}-${day} ${time} KST`;
}

/** 그 날짜가 (해외에서 열어도) 한국 기준 오늘인지. */
function isTodayInKst(date: string): boolean {
  try {
    const today = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Seoul",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date());
    return today === date;
  } catch {
    // 판단이 안 되면 날짜까지 보여 줍니다. 정보가 적은 쪽보다 낫습니다.
    return false;
  }
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
