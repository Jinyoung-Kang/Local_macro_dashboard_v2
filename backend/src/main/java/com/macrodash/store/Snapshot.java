package com.macrodash.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 저장된 스냅샷 1건과 그 신선도.
 *
 * @param name        데이터셋 이름 ({@link Datasets})
 * @param payload     수집기가 적재한 JSON
 * @param kind        json | frame | object (구버전 호환 표시)
 * @param status      ok | estimated | error
 * @param error       실패 사유 (있으면)
 * @param collectedAt 수집 시각
 */
public record Snapshot(
        String name,
        JsonNode payload,
        String kind,
        String status,
        String error,
        Instant collectedAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST);

    public long ageSeconds() {
        if (collectedAt == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, Duration.between(collectedAt, Instant.now()).getSeconds());
    }

    public boolean isFresh(long maxAgeSeconds) {
        return ageSeconds() <= maxAgeSeconds;
    }

    /**
     * 추정치 여부.
     *
     * <p>추정치를 확정치처럼 보여 주면 교차 검증도 무의미해집니다. 화면은 이
     * 값을 보고 반드시 경고를 띄워야 합니다.
     */
    public boolean isEstimated() {
        return "estimated".equals(status);
    }

    public String collectedAtKst() {
        return collectedAt == null ? "알 수 없음" : KST_FORMAT.format(collectedAt) + " KST";
    }
}
