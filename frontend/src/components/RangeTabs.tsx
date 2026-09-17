"use client";

/**
 * 차트 기간 선택.
 *
 * 이미 받아 둔 시계열을 자르기만 하므로 즉시 반응하고, 서버를 다시 부르지
 * 않습니다(FRED 호출도 늘지 않습니다).
 *
 * 필터는 차트 카드 안이 아니라 **카드 머리말 줄**에 둡니다. 어느 차트에
 * 걸리는 필터인지 눈으로 바로 이어지게 하기 위해서입니다.
 */
// "전체"는 두지 않습니다.
//
// 수집기가 FRED에서 받아 두는 구간이 10년 + 90일입니다
// (collector/app/services/fred.py: period_years=10). 저장본에 그보다 옛날
// 값이 없으므로 "전체" 탭을 만들면 10년과 **똑같은 그림**이 나오고,
// 읽는 사람은 1982년부터의 T10Y3M을 보고 있다고 오해합니다.
// 범위를 넓히려면 탭이 아니라 수집 구간부터 늘려야 합니다.
export const RANGES = [
  { value: "1y", label: "1년", months: 12 },
  { value: "3y", label: "3년", months: 36 },
  { value: "5y", label: "5년", months: 60 },
  { value: "10y", label: "10년", months: 120 },
] as const;

export type RangeValue = (typeof RANGES)[number]["value"];

/** 선택한 기간만큼 뒤에서 잘라 냅니다. 원본은 건드리지 않습니다. */
export function sliceByRange<T extends { date: string }>(
  points: T[],
  range: RangeValue,
): T[] {
  const months = RANGES.find((entry) => entry.value === range)?.months ?? null;
  if (months === null || points.length === 0) {
    return points;
  }

  // 마지막 관측일 기준으로 자릅니다. "오늘" 기준으로 자르면 수집이 며칠
  // 밀렸을 때 구간이 통째로 비어 버립니다.
  const last = new Date(points[points.length - 1].date);
  if (Number.isNaN(last.getTime())) {
    return points;
  }
  const cutoff = new Date(last);
  cutoff.setMonth(cutoff.getMonth() - months);

  const cutoffText = cutoff.toISOString().slice(0, 10);
  return points.filter((point) => point.date >= cutoffText);
}

export function RangeTabs({
  value,
  onChange,
  label = "기간",
}: {
  value: RangeValue;
  onChange: (next: RangeValue) => void;
  label?: string;
}) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-[11px] text-muted">{label}</span>
      <div
        role="group"
        aria-label={label}
        className="inline-flex overflow-hidden rounded-md border border-border"
      >
        {RANGES.map((entry) => {
          const active = entry.value === value;
          return (
            <button
              key={entry.value}
              type="button"
              aria-pressed={active}
              onClick={() => onChange(entry.value)}
              className={`px-2.5 py-1 text-[11px] tabular-nums transition ${
                active
                  ? "bg-accent/20 font-semibold text-accent"
                  : "text-muted hover:bg-surface-hover hover:text-body"
              }`}
            >
              {entry.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
