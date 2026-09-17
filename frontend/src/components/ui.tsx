"use client";

import { ReactNode } from "react";
import { deltaColor, EMPTY, formatAge, formatCollectedAtKst } from "@/lib/format";

/** 섹션 카드 컨테이너. */
export function Card({
  title,
  subtitle,
  actions,
  children,
  className = "",
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`rounded-lg border border-border bg-surface p-4 sm:p-5 ${className}`}
    >
      {(title || actions) && (
        <header className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            {title && <h2 className="text-base font-semibold text-bright">{title}</h2>}
            {subtitle && <p className="mt-1 text-xs text-muted">{subtitle}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

/** 지표 카드 (구버전 st.metric에 해당). */
export function Metric({
  label,
  value,
  delta,
  deltaText,
  caption,
  note,
  tone,
}: {
  label: ReactNode;
  value: ReactNode;
  delta?: number | null;
  deltaText?: string;
  caption?: ReactNode;
  note?: ReactNode;
  tone?: string;
}) {
  return (
    <div className="rounded-lg border border-border bg-surface px-4 py-3">
      <div className="text-xs font-medium text-muted">{label}</div>
      <div className={`mt-1 text-xl font-bold tabular-nums ${tone ?? "text-bright"}`}>
        {value}
      </div>
      {(deltaText || delta !== undefined) && (
        <div className={`mt-1 text-xs tabular-nums ${deltaColor(delta)}`}>
          {deltaText ?? EMPTY}
        </div>
      )}
      {caption && <div className="mt-1 text-[11px] text-muted">{caption}</div>}
      {note && <div className="mt-2 text-[11px] leading-relaxed text-muted">{note}</div>}
    </div>
  );
}

/**
 * 데이터 신선도 배지 — "언제 수집한 값인지"를 항상 함께 보여 줍니다.
 *
 * <p>경과 시간("1초 전")이 아니라 <b>수집한 시각</b>을 적습니다. 경과 시간은
 * 화면을 연 순간을 기준으로 계산되므로, 대시보드를 띄워 둔 채 한참 뒤에 보면
 * "1초 전"이라고 적혀 있어도 실제로는 한 시간 전 값일 수 있습니다. 시각은
 * 화면이 멈춰 있어도 틀리지 않습니다. 경과 시간은 배지에 마우스를 올리면
 * 보이도록 남겨 둡니다.
 */
export function Freshness({
  collectedAt,
  ageSeconds,
  stale,
}: {
  /** 백엔드가 KST로 찍어 준 수집 시각 문자열 (collectedAtKst). */
  collectedAt?: string | null;
  ageSeconds?: number | null;
  stale?: boolean;
}) {
  if (!collectedAt && ageSeconds === undefined) {
    return null;
  }
  const age = formatAge(ageSeconds);

  return (
    <span
      className={`inline-flex items-center gap-1 rounded border px-2 py-0.5 text-[11px] ${
        stale
          ? "border-warn/40 bg-warn/10 text-warn"
          : "border-border bg-surface-hover text-muted"
      }`}
      title={collectedAt ? `${collectedAt} · 조회 시점 기준 ${age}` : undefined}
    >
      {stale ? "⚠️ 오래된 저장본" : "🕒 수집"}
      {/* 시각을 모르면 그때만 경과 시간으로 대신합니다 — 아무것도 안 적는 것보다 낫습니다. */}
      <span className="tabular-nums">
        {collectedAt ? formatCollectedAtKst(collectedAt) : age}
      </span>
    </span>
  );
}

/** 경고 배너 — 추정치·이력 대체처럼 "값의 성격"을 알려야 할 때. */
export function Banner({
  tone = "warn",
  children,
}: {
  tone?: "warn" | "danger" | "info";
  children: ReactNode;
}) {
  const palette = {
    warn: "border-warn/40 bg-warn/10 text-warn",
    danger: "border-danger/40 bg-danger/10 text-danger",
    info: "border-accent/40 bg-accent/10 text-accent",
  }[tone];

  return (
    <div className={`rounded-lg border px-4 py-3 text-sm leading-relaxed ${palette}`}>
      {children}
    </div>
  );
}

export function Loading({ label = "불러오는 중…" }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-8 text-sm text-muted">
      <span className="h-3 w-3 animate-pulse rounded-full bg-accent" />
      {label}
    </div>
  );
}

export function EmptyState({ message }: { message: string }) {
  return (
    <div className="rounded-lg border border-dashed border-border px-4 py-8 text-center text-sm text-muted">
      {message}
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="rounded-lg border border-danger/40 bg-danger/10 px-4 py-4 text-sm text-danger">
      <p>{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 rounded border border-danger/40 px-3 py-1 text-xs hover:bg-danger/20"
        >
          다시 시도
        </button>
      )}
    </div>
  );
}

export function Button({
  children,
  onClick,
  variant = "default",
  disabled,
  type = "button",
  className = "",
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: "default" | "primary" | "ghost";
  disabled?: boolean;
  type?: "button" | "submit";
  className?: string;
}) {
  const palette = {
    default: "border-border bg-surface-hover text-body hover:border-muted hover:text-bright",
    primary: "border-accent/60 bg-accent/15 text-accent hover:bg-accent/25",
    ghost: "border-transparent bg-transparent text-muted hover:text-bright",
  }[variant];

  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={`rounded-md border px-3 py-1.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-50 ${palette} ${className}`}
    >
      {children}
    </button>
  );
}

export function Select({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <label className="flex flex-col gap-1 text-xs text-muted">
      {label}
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="rounded-md border border-border bg-surface-hover px-2 py-1.5 text-sm text-body outline-none focus:border-accent"
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

/** 단순 테이블. 컬럼 정의로 정렬/포맷을 제어합니다. */
export function Table<T>({
  columns,
  rows,
  emptyMessage = "표시할 데이터가 없습니다.",
  rowKey,
}: {
  columns: {
    key: string;
    header: ReactNode;
    align?: "left" | "right";
    render: (row: T, index: number) => ReactNode;
    className?: string;
  }[];
  rows: T[];
  emptyMessage?: string;
  rowKey: (row: T, index: number) => string;
}) {
  if (rows.length === 0) {
    return <EmptyState message={emptyMessage} />;
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] border-collapse text-sm">
        <thead>
          <tr className="border-b border-border text-xs uppercase tracking-wide text-muted">
            {columns.map((column) => (
              <th
                key={column.key}
                className={`px-3 py-2 font-medium ${
                  column.align === "right" ? "text-right" : "text-left"
                }`}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr
              key={rowKey(row, index)}
              className="border-b border-border/50 last:border-0 hover:bg-surface-hover/60"
            >
              {columns.map((column) => (
                <td
                  key={column.key}
                  className={`px-3 py-2 tabular-nums ${
                    column.align === "right" ? "text-right" : "text-left"
                  } ${column.className ?? ""}`}
                >
                  {column.render(row, index)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function SourceBadge({ children }: { children: ReactNode }) {
  return (
    <span className="rounded border border-border bg-surface-hover px-2 py-0.5 text-[11px] text-muted">
      {children}
    </span>
  );
}
