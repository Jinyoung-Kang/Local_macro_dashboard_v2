package com.macrodash.analytics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 🧬 기관 포트폴리오의 성격을 숫자로 재는 계산들.
 *
 * <p>13F 화면은 "누가 무엇을 들고 있나"까지는 보여 주지만, <b>어떤 식으로</b>
 * 들고 있는지는 보여 주지 않았습니다. 10종목에 몰아넣은 포트폴리오와 3,000종목에
 * 흩뿌린 포트폴리오는 같은 표로 읽히면 안 됩니다.
 *
 * <p>모든 계산은 <b>비중 벡터</b>(종목 키 → 비중 %)만 받습니다. 가격도, 재무
 * 데이터도 필요 없습니다 — 13F 공시에 있는 것만 씁니다.
 *
 * <p>⚠️ 13F가 담지 않는 것: 미국 상장 <b>롱 포지션</b>만 공시 대상입니다. 채권·
 * 현금·해외 상장분·공매도는 애초에 이 숫자에 들어 있지 않습니다. 화면이 이 한계를
 * 함께 적어야 합니다.
 */
public final class GuruStyle {

    private GuruStyle() {
    }

    /**
     * 겹침 비중 — 두 포트폴리오의 몇 %가 같은 종목인가.
     *
     * <p>각 종목에서 <b>작은 쪽 비중</b>을 더합니다. 애플을 한쪽이 10%,
     * 다른 쪽이 3% 들고 있으면 겹치는 것은 3%입니다.
     *
     * <p><b>왜 코사인보다 이것을 머리기사로 두는가</b> — 코사인 0.82는 해석이
     * 필요하지만, "62% 겹칩니다"는 그대로 읽힙니다. 코사인은 방향만 보기 때문에
     * 종목 수가 크게 다른 두 포트폴리오에서 직관과 어긋나기도 합니다.
     */
    public static double overlapWeight(Map<String, Double> a, Map<String, Double> b) {
        double sum = 0.0;
        for (Map.Entry<String, Double> entry : a.entrySet()) {
            Double other = b.get(entry.getKey());
            if (entry.getValue() != null && other != null) {
                sum += Math.min(entry.getValue(), other);
            }
        }
        return sum;
    }

    /**
     * 코사인 유사도 (0~1).
     *
     * <p>비중의 <b>모양</b>이 얼마나 닮았는지 봅니다. 규모가 달라도 같은 비율로
     * 담았다면 1에 가깝습니다. 공통 종목이 없으면 0입니다.
     */
    public static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) {
            double left = a.getOrDefault(key, 0.0);
            double right = b.getOrDefault(key, 0.0);
            dot += left * right;
            normA += left * left;
            normB += right * right;
        }
        if (normA <= 0 || normB <= 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 허핀달 집중도 (0~1). 비중을 비율로 바꿔 제곱해 더합니다.
     *
     * <p>한 종목에 100%면 1, 100종목에 균등하면 0.01입니다.
     */
    public static double herfindahl(Map<String, Double> weights) {
        double sum = 0.0;
        for (Double weight : weights.values()) {
            if (weight != null) {
                double ratio = weight / 100.0;
                sum += ratio * ratio;
            }
        }
        return sum;
    }

    /**
     * 유효 종목 수 = 1 / HHI.
     *
     * <p>"몇 종목을 들고 있나"보다 정직한 숫자입니다. 3,000종목을 들고 있어도
     * 상위 몇 개에 쏠려 있으면 유효 종목 수는 수십 개로 나옵니다. 종목 수만
     * 세면 인덱스 펀드와 집중 투자자가 같은 칸에 놓입니다.
     */
    public static Double effectiveHoldings(Map<String, Double> weights) {
        double hhi = herfindahl(weights);
        return hhi <= 0 ? null : 1.0 / hhi;
    }

    /**
     * 분기 회전율 (%) = Σ|비중 변화| ÷ 2.
     *
     * <p>2로 나누는 이유 — 한 종목을 팔아 다른 종목을 사면 비중 변화가 두 번
     * 잡힙니다(-5%p, +5%p). 실제로 갈아탄 것은 5%인데 10%로 세면 안 됩니다.
     *
     * <p>100%는 포트폴리오가 통째로 바뀌었다는 뜻입니다. 직전 분기가 없으면
     * null입니다 — 0으로 두면 "한 주도 안 바꿨다"로 읽힙니다.
     */
    public static Double turnover(Map<String, Double> previous, Map<String, Double> current) {
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) {
            return null;
        }
        Set<String> keys = new HashSet<>(previous.keySet());
        keys.addAll(current.keySet());

        double sum = 0.0;
        for (String key : keys) {
            sum += Math.abs(current.getOrDefault(key, 0.0) - previous.getOrDefault(key, 0.0));
        }
        return sum / 2.0;
    }

    /** 상위 n종목이 차지하는 비중 합. */
    public static double topWeight(Map<String, Double> weights, int n) {
        return weights.values().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.reverseOrder())
                .limit(Math.max(1, n))
                .mapToDouble(Double::doubleValue)
                .sum();
    }
}
