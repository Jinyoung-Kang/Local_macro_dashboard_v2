package com.macrodash.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/**
 * 두 시계열의 상관관계.
 *
 * <p><b>왜 "변화(Δ)"를 기본으로 두는가</b> — 수준(level)끼리의 상관은 둘 다
 * 추세를 갖기만 해도 0.9가 넘습니다(허위 상관). 순유동성과 나스닥은 10년 동안
 * 둘 다 우상향했으니 수준 상관은 당연히 높지만, 그 숫자는 "유동성이 늘면 주가가
 * 오른다"를 증명하지 않습니다. 변화끼리 비교해야 "같이 움직이는가"를 봅니다.
 * 수준 상관도 볼 수 있게 두되, 화면이 경고를 함께 띄웁니다.
 *
 * <p>모든 계산은 <b>두 계열이 모두 값을 가진 날짜</b>만 씁니다. 한쪽이 주간
 * (NFCI)이고 다른 쪽이 일간이면 교집합은 주 1회가 됩니다 — 그래서 표본 수를
 * 반드시 함께 돌려줍니다.
 */
public final class Correlation {

    private Correlation() {
    }

    /** 같은 날짜만 남긴 두 계열. */
    public record Aligned(List<LocalDate> dates, double[] x, double[] y) {

        public int size() {
            return x.length;
        }
    }

    /** 두 계열에서 날짜가 겹치는 지점만 추립니다. */
    public static Aligned align(NavigableMap<LocalDate, Double> left,
                                NavigableMap<LocalDate, Double> right) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();

        for (var entry : left.entrySet()) {
            Double other = right.get(entry.getKey());
            if (entry.getValue() == null || other == null) {
                continue;
            }
            if (!Double.isFinite(entry.getValue()) || !Double.isFinite(other)) {
                continue;
            }
            dates.add(entry.getKey());
            xs.add(entry.getValue());
            ys.add(other);
        }
        return new Aligned(dates, toArray(xs), toArray(ys));
    }

    /**
     * 직전 관측 대비 변화(Δ)로 바꿉니다.
     *
     * <p>가격이든 금리든 같은 방식(차분)을 씁니다. 상관계수는 단위와 배율에
     * 영향을 받지 않으므로, 한쪽만 수익률로 바꿔 계산을 복잡하게 만들 이유가
     * 없습니다.
     */
    public static Aligned toChanges(Aligned aligned) {
        int n = aligned.size();
        if (n < 2) {
            return new Aligned(List.of(), new double[0], new double[0]);
        }
        List<LocalDate> dates = new ArrayList<>(n - 1);
        double[] dx = new double[n - 1];
        double[] dy = new double[n - 1];
        for (int i = 1; i < n; i++) {
            dates.add(aligned.dates().get(i));
            dx[i - 1] = aligned.x()[i] - aligned.x()[i - 1];
            dy[i - 1] = aligned.y()[i] - aligned.y()[i - 1];
        }
        return new Aligned(dates, dx, dy);
    }

    /**
     * 피어슨 상관계수. 표본이 3개 미만이거나 한쪽이 상수면 null입니다.
     *
     * <p>상수 계열에 0을 돌려주면 "상관이 없다"로 읽히는데, 사실은 "계산할 수
     * 없다"입니다. 이 프로젝트의 규칙대로 모르는 것은 null로 둡니다.
     */
    public static Double pearson(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length || x.length < 3) {
            return null;
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < x.length; i++) {
            double a = x[i] - meanX;
            double b = y[i] - meanY;
            sxy += a * b;
            sxx += a * a;
            syy += b * b;
        }
        if (sxx <= 0 || syy <= 0) {
            return null;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    /**
     * 롤링 상관계수. 창을 채우지 못한 앞부분은 null입니다(0으로 채우지 않습니다).
     */
    public static List<Double> rolling(double[] x, double[] y, int window) {
        List<Double> out = new ArrayList<>();
        if (x == null || y == null || x.length != y.length || window < 3) {
            return out;
        }
        for (int i = 0; i < x.length; i++) {
            if (i + 1 < window) {
                out.add(null);
                continue;
            }
            double[] wx = new double[window];
            double[] wy = new double[window];
            System.arraycopy(x, i + 1 - window, wx, 0, window);
            System.arraycopy(y, i + 1 - window, wy, 0, window);
            out.add(pearson(wx, wy));
        }
        return out;
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
