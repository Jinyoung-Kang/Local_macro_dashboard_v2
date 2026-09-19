/**
 * 거래소 휴장일 판정.
 *
 * 왜 공휴일 API를 쓰지 않는가
 * --------------------------
 * 예전에는 브라우저가 date.nager.at의 "공휴일" 목록을 받아 휴장을 판정했습니다.
 * **공휴일과 거래소 휴장일은 다릅니다.**
 *
 *   - 컬럼버스데이·재향군인의 날: 미국 연방 공휴일이지만 증시는 **열립니다**
 *     (채권시장만 휴장). 공휴일 목록으로 판정하면 "휴장"이라고 거짓말합니다.
 *   - 성금요일(Good Friday): 연방 공휴일이 **아니지만** 증시는 닫습니다.
 *     공휴일 목록에 없으므로 "거래 중"이라고 거짓말합니다.
 *
 * 게다가 페이지를 열 때마다 외부 API로 요청이 나갔습니다. 이 프로젝트는
 * "화면이 필요한 데이터는 모두 우리 백엔드를 거친다"를 지켜 왔는데 시계만
 * 예외였습니다.
 *
 * 미국은 규칙으로 계산하고(NYSE/NASDAQ 휴장 규칙은 날짜 규칙이라 계산 가능),
 * 한국은 음력이 섞여 규칙화할 수 없으므로 표로 둡니다.
 */

/** 로컬 날짜를 YYYY-MM-DD로. Date를 UTC로 바꾸지 않습니다. */
function iso(year: number, month: number, day: number): string {
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

/**
 * 그 달의 n번째 특정 요일 날짜.
 *
 * @param year   연도
 * @param month  1~12
 * @param weekday 0=일 … 6=토
 * @param n      1이면 첫 번째, 3이면 세 번째
 * @returns 일(day) 숫자
 */
function nthWeekday(year: number, month: number, weekday: number, n: number): number {
  const first = new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
  const offset = (weekday - first + 7) % 7;
  return 1 + offset + (n - 1) * 7;
}

/** 그 달의 마지막 특정 요일 날짜. */
function lastWeekday(year: number, month: number, weekday: number): number {
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const last = new Date(Date.UTC(year, month - 1, lastDay)).getUTCDay();
  return lastDay - ((last - weekday + 7) % 7);
}

/**
 * 부활절 일요일 (Anonymous Gregorian algorithm).
 *
 * 성금요일 = 부활절 이틀 전이며, 미국 증시가 닫는 날인데 연방 공휴일이
 * 아니라서 공휴일 목록에는 없습니다.
 *
 * @returns [월, 일]
 */
function easterSunday(year: number): [number, number] {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return [month, day];
}

/**
 * 고정 날짜 휴일의 실제 휴장일.
 *
 * 미국 증시는 휴일이 토요일이면 **전날 금요일**, 일요일이면 **다음 월요일**에
 * 쉽니다. 이 규칙을 빠뜨리면 7월 4일이 토요일인 해에 7월 3일을 개장으로
 * 판정하게 됩니다.
 *
 * @returns YYYY-MM-DD
 */
function observed(year: number, month: number, day: number): string {
  const weekday = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
  if (weekday === 6) {
    return iso(year, month, day - 1);
  }
  if (weekday === 0) {
    return iso(year, month, day + 1);
  }
  return iso(year, month, day);
}

/**
 * NYSE·NASDAQ 정규장 휴장일.
 *
 * 규칙으로 계산하므로 해가 바뀌어도 손댈 필요가 없습니다.
 *
 * 주의: 조기 폐장일(추수감사절 다음 날, 12월 24일 등 13:00 종료)은 담지
 * 않습니다. 이 화면은 "열렸나/닫혔나"만 말하고 조기 폐장은 다루지 않습니다.
 *
 * @param year 연도
 * @returns YYYY-MM-DD 집합
 */
export function usMarketHolidays(year: number): Set<string> {
  const [easterMonth, easterDay] = easterSunday(year);
  const goodFriday = new Date(Date.UTC(year, easterMonth - 1, easterDay - 2));

  return new Set<string>([
    observed(year, 1, 1),                                   // 신정
    iso(year, 1, nthWeekday(year, 1, 1, 3)),                // 마틴 루터 킹 데이 (1월 셋째 월)
    iso(year, 2, nthWeekday(year, 2, 1, 3)),                // 대통령의 날 (2월 셋째 월)
    iso(year, goodFriday.getUTCMonth() + 1, goodFriday.getUTCDate()), // 성금요일
    iso(year, 5, lastWeekday(year, 5, 1)),                  // 메모리얼 데이 (5월 마지막 월)
    observed(year, 6, 19),                                  // 준틴스
    observed(year, 7, 4),                                   // 독립기념일
    iso(year, 9, nthWeekday(year, 9, 1, 1)),                // 노동절 (9월 첫째 월)
    iso(year, 11, nthWeekday(year, 11, 4, 4)),              // 추수감사절 (11월 넷째 목)
    observed(year, 12, 25),                                 // 크리스마스
  ]);
}

/**
 * KRX 휴장일.
 *
 * 두 갈래로 나눕니다.
 *
 *   1. **양력 고정 휴일** — 신정·삼일절·근로자의 날·어린이날·현충일·광복절·
 *      개천절·한글날·성탄절·연말 폐장일. 날짜가 고정이라 계산합니다.
 *   2. **음력 휴일** — 설날·추석·부처님오신날. 해마다 양력 날짜가 달라
 *      계산할 수 없어 아래 표에 적습니다.
 *
 * 표에 없는 해는 **양력 휴일만** 반영하고, 화면은 `hasLunarHolidays()`로
 * "설날·추석은 반영되지 않았습니다"를 함께 띄웁니다. 모르는 것을 아는 척
 * 하지 않습니다.
 *
 * @param year 연도
 * @returns YYYY-MM-DD 집합
 */
export function krMarketHolidays(year: number): Set<string> {
  const days = new Set<string>([
    iso(year, 1, 1),    // 신정
    iso(year, 3, 1),    // 삼일절
    iso(year, 5, 1),    // 근로자의 날 (KRX 휴장)
    iso(year, 5, 5),    // 어린이날
    iso(year, 6, 6),    // 현충일
    iso(year, 8, 15),   // 광복절
    iso(year, 10, 3),   // 개천절
    iso(year, 10, 9),   // 한글날
    iso(year, 12, 25),  // 성탄절
    iso(year, 12, 31),  // 연말 폐장일
  ]);
  for (const day of KRX_LUNAR_HOLIDAYS[year] ?? []) {
    days.add(day);
  }
  return days;
}

/**
 * 그 해의 음력 휴일을 표에 갖고 있는지.
 *
 * false면 설날·추석 연휴가 "거래 중"으로 잘못 표시될 수 있으므로 화면이
 * 안내를 띄웁니다.
 */
export function hasLunarHolidays(year: number): boolean {
  return KRX_LUNAR_HOLIDAYS[year] !== undefined;
}

/**
 * 음력 기반 KRX 휴장일 (설날·부처님오신날·추석 연휴).
 *
 * ⚠️ **해마다 확인이 필요합니다.** 대체공휴일 지정과 임시공휴일 때문에
 * 연휴 길이가 바뀝니다. 한국거래소가 매년 말 다음 해 휴장일을 공지하므로
 * (krx.co.kr → 시장운영 → 휴장일 안내) 그 표를 보고 한 줄씩 맞추세요.
 *
 * 틀려도 데이터·계산에는 영향이 없습니다 — 시계 배지의 문구만 달라집니다.
 */
const KRX_LUNAR_HOLIDAYS: Record<number, string[]> = {
  2025: ["2025-01-28", "2025-01-29", "2025-01-30", "2025-05-06", "2025-10-06", "2025-10-07", "2025-10-08"],
  2026: ["2026-02-16", "2026-02-17", "2026-02-18", "2026-05-25", "2026-09-24", "2026-09-25"],
  2027: ["2027-02-08", "2027-02-09", "2027-05-13", "2027-09-14", "2027-09-15", "2027-09-16"],
};
