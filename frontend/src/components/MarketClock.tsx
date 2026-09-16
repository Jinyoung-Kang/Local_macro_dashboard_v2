"use client";

import { useEffect, useState } from "react";

/**
 * 실시간 거래소 시계 + 장 상태 배지.
 *
 * 구버전은 base64로 인코딩한 HTML을 iframe에 넣어 표시했습니다. React에서는
 * 컴포넌트로 직접 그립니다. 공휴일은 공개 API(date.nager.at)에서 연 1회
 * 받아 캐시하고, 실패하면 주말만 판정합니다(있는 정보로만 판단).
 */

type MarketStatus = {
  text: string;
  className: string;
};

const STATUS_STYLES: Record<string, string> = {
  trading: "bg-ok/15 text-ok border-ok/40",
  pre: "bg-warn/15 text-warn border-warn/40",
  post: "bg-purple-500/15 text-purple-300 border-purple-500/40",
  closed: "bg-muted/15 text-muted border-muted/40",
};

function partsFor(timeZone: string, now: Date) {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    weekday: "short",
  });

  const parts = Object.fromEntries(
    formatter.formatToParts(now).map((part) => [part.type, part.value]),
  );

  return {
    date: `${parts.year}-${parts.month}-${parts.day}`,
    time: `${parts.hour}:${parts.minute}:${parts.second}`,
    minutes: Number(parts.hour) * 60 + Number(parts.minute),
    weekday: String(parts.weekday),
  };
}

function statusFor(
  market: "KOSPI" | "NASDAQ",
  minutes: number,
  weekday: string,
  isHoliday: boolean,
): MarketStatus {
  if (weekday === "Sat" || weekday === "Sun") {
    return { text: "휴장 (주말)", className: STATUS_STYLES.closed };
  }
  if (isHoliday) {
    return { text: "휴장 (공휴일)", className: STATUS_STYLES.closed };
  }

  if (market === "KOSPI") {
    if (minutes >= 9 * 60 && minutes < 15 * 60 + 30) {
      return { text: "거래 중", className: STATUS_STYLES.trading };
    }
    if (minutes >= 8 * 60 + 30 && minutes < 9 * 60) {
      return { text: "프리마켓", className: STATUS_STYLES.pre };
    }
    if (minutes >= 15 * 60 + 30 && minutes <= 18 * 60) {
      return { text: "애프터마켓", className: STATUS_STYLES.post };
    }
    return { text: "장 마감", className: STATUS_STYLES.closed };
  }

  if (minutes >= 9 * 60 + 30 && minutes < 16 * 60) {
    return { text: "거래 중", className: STATUS_STYLES.trading };
  }
  if (minutes >= 4 * 60 && minutes < 9 * 60 + 30) {
    return { text: "프리마켓", className: STATUS_STYLES.pre };
  }
  if (minutes >= 16 * 60 && minutes <= 20 * 60) {
    return { text: "애프터마켓", className: STATUS_STYLES.post };
  }
  return { text: "장 마감", className: STATUS_STYLES.closed };
}

export function MarketClock() {
  const [now, setNow] = useState<Date | null>(null);
  const [holidays, setHolidays] = useState<{ kr: Set<string>; us: Set<string> }>({
    kr: new Set(),
    us: new Set(),
  });

  useEffect(() => {
    // 서버 렌더 시각과 클라이언트 시각이 달라 하이드레이션 경고가 나는 것을
    // 막기 위해, 마운트 후에만 시각을 그립니다.
    setNow(new Date());
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const year = new Date().getFullYear();
    const load = async () => {
      try {
        const [kr, us] = await Promise.all([
          fetch(`https://date.nager.at/api/v3/PublicHolidays/${year}/KR`),
          fetch(`https://date.nager.at/api/v3/PublicHolidays/${year}/US`),
        ]);
        const krDates = kr.ok
          ? new Set<string>((await kr.json()).map((item: { date: string }) => item.date))
          : new Set<string>();
        const usDates = us.ok
          ? new Set<string>((await us.json()).map((item: { date: string }) => item.date))
          : new Set<string>();

        // 한국 거래소는 근로자의 날과 연말 폐장일도 휴장입니다.
        krDates.add(`${year}-05-01`);
        krDates.add(`${year}-12-31`);

        setHolidays({ kr: krDates, us: usDates });
      } catch {
        // 공휴일 정보를 못 받으면 주말만 판정합니다. 추측하지 않습니다.
      }
    };
    void load();
  }, []);

  if (!now) {
    return <div className="h-[46px] rounded-lg border border-border bg-surface" />;
  }

  const kst = partsFor("Asia/Seoul", now);
  const est = partsFor("America/New_York", now);
  const kstStatus = statusFor("KOSPI", kst.minutes, kst.weekday, holidays.kr.has(kst.date));
  const estStatus = statusFor("NASDAQ", est.minutes, est.weekday, holidays.us.has(est.date));

  return (
    <div className="flex flex-wrap items-center gap-4 rounded-lg border border-border bg-surface px-4 py-2 text-sm">
      <ClockEntry
        flag="🇰🇷"
        label="한국 (KOSPI)"
        date={kst.date}
        time={kst.time}
        status={kstStatus}
      />
      <ClockEntry
        flag="🗽"
        label="뉴욕 (NASDAQ)"
        date={est.date}
        time={est.time}
        status={estStatus}
      />
    </div>
  );
}

function ClockEntry({
  flag,
  label,
  date,
  time,
  status,
}: {
  flag: string;
  label: string;
  date: string;
  time: string;
  status: MarketStatus;
}) {
  return (
    <div className="flex items-center gap-2">
      <span>{flag}</span>
      <span className="text-muted">{label}</span>
      <span className="font-mono tabular-nums text-bright">
        {date} {time}
      </span>
      <span className={`rounded border px-2 py-0.5 text-[11px] font-semibold ${status.className}`}>
        {status.text}
      </span>
    </div>
  );
}
