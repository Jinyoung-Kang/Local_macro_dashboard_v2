package com.macrodash.store;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 읽기 모드에 따른 저장본 접근 (구버전 {@code store.cached_or_live}에 해당).
 *
 * <p>규칙
 * <ul>
 *   <li><b>auto</b> — 저장본이 신선하면 그대로 씁니다. 오래됐으면 수집기에
 *       해당 태스크를 요청하고 다시 읽습니다. <b>수집이 실패하면 오래된
 *       저장본이라도 돌려줍니다</b> — 외부 장애 때 화면이 비는 것보다 낫고,
 *       화면에는 수집 시각이 함께 표시되므로 오해 여지가 없습니다.</li>
 *   <li><b>store_only</b> — 저장본만 씁니다. 오래됐어도 그대로 주고, 없으면
 *       비어 있다고 답합니다. "화면이 절대 외부를 기다리지 않는다"는 약속이
 *       신선도보다 우선입니다.</li>
 *   <li><b>live_only</b> — 항상 수집을 요청한 뒤 읽습니다(디버깅용).</li>
 * </ul>
 *
 * <p>수동 새로고침은 저장본을 지우지 않습니다. "이 시각 이전 저장본은 낡은
 * 것으로 본다"는 기준({@code refresh_requests})만 세웁니다. 저장본을 지우면
 * 수집이 실패했을 때 보여 줄 값이 아예 없어지기 때문입니다.
 */
@Service
public class StoreReader {

    /**
     * 같은 태스크 재요청을 억제하는 시간.
     *
     * <p>수집 자체가 보통 수 초~수십 초 걸리므로, 그 안에 다시 요청해 봐야
     * 같은 수집을 기다리게 될 뿐입니다.
     */
    private static final long TRIGGER_COOLDOWN_MS = 30_000;

    private final Map<String, Long> lastTriggeredAt = new ConcurrentHashMap<>();


    private static final Logger log = LoggerFactory.getLogger(StoreReader.class);

    private final StoreRepository repository;
    private final CollectorClient collector;
    private final AppProperties properties;

    public StoreReader(StoreRepository repository,
                       CollectorClient collector,
                       AppProperties properties) {
        this.repository = repository;
        this.collector = collector;
        this.properties = properties;
    }

    public AppProperties.ReadMode readMode() {
        return properties.resolvedReadMode();
    }

    /**
     * 저장본을 읽습니다. 필요하면 수집기에 수집을 요청합니다.
     *
     * @param name         데이터셋 이름
     * @param maxAgeSeconds 이 시간보다 오래되면 다시 수집
     * @param taskName     수집을 맡길 수집기 태스크 이름 (null이면 요청하지 않음)
     */
    public Optional<Snapshot> read(String name, long maxAgeSeconds, String taskName) {
        AppProperties.ReadMode mode = readMode();
        Optional<Snapshot> stored = repository.readSnapshot(name);

        if (mode == AppProperties.ReadMode.STORE_ONLY) {
            return stored;
        }

        boolean stale = stored.isEmpty()
                || !stored.get().isFresh(maxAgeSeconds)
                || supersededByRefresh(stored.get());

        if (mode == AppProperties.ReadMode.AUTO && !stale) {
            return stored;
        }

        if (taskName == null) {
            return stored;
        }

        // ── 보여 줄 저장본이 이미 있으면 기다리지 않습니다 ──────────────
        // 이 프로젝트의 규칙입니다: "화면은 수집을 기다리지 않습니다."
        // 예전에는 수집이 끝날 때까지 붙잡고 있어서, 화면 한 번 여는 데
        // sec_13f 31.8초 · fred_series 11.5초가 그대로 대기 시간이 됐습니다.
        // 저장본은 신선도 배지가 "언제 수집한 값인지"를 이미 보여 주고,
        // 수집이 끝나면 다음 조회에서 새 값이 나옵니다.
        if (stored.isPresent()) {
            if (shouldTrigger(taskName)) {
                log.info("저장본이 오래됐습니다({}). 수집기에 '{}'를 요청하고, "
                        + "화면에는 기존 저장본을 먼저 보여 줍니다.", name, taskName);
                collector.runTask(taskName, false);
            }
            return stored;
        }

        // 보여 줄 것이 아예 없을 때만 기다립니다. 빈 화면보다는 나은 선택입니다.
        log.info("저장본이 없습니다({}). 수집기에 '{}' 태스크를 요청하고 기다립니다.",
                name, taskName);
        Optional<?> triggered = collector.runTask(taskName, true);

        Optional<Snapshot> refreshed = repository.readSnapshot(name);
        if (refreshed.isPresent()) {
            return refreshed;
        }

        if (triggered.isEmpty()) {
            log.warn("수집 요청 실패({}). 남아 있는 저장본으로 대체합니다.", name);
        }
        return stored;
    }

    /**
     * 같은 태스크를 짧은 시간에 반복 요청하지 않도록 거릅니다.
     *
     * <p>화면 하나가 여러 스냅샷을 병렬로 읽으면 같은 태스크 요청이 그만큼
     * 나갑니다. 실제 로그에서 한 번의 페이지 로드에 fred_series 요청이
     * 7건이었습니다. 수집기가 합쳐 주기는 하지만, 애초에 보내지 않는 편이
     * 왕복과 실행 기록을 아낍니다.
     */
    private boolean shouldTrigger(String taskName) {
        long now = System.currentTimeMillis();
        Long previous = lastTriggeredAt.get(taskName);
        if (previous != null && now - previous < TRIGGER_COOLDOWN_MS) {
            return false;
        }
        // 동시에 들어온 요청 중 하나만 통과시킵니다.
        Long raced = lastTriggeredAt.put(taskName, now);
        return raced == null || now - raced >= TRIGGER_COOLDOWN_MS;
    }

    /** 수집 요청 없이 저장본만 읽습니다. */
    public Optional<Snapshot> readStored(String name) {
        return repository.readSnapshot(name);
    }

    /**
     * 이 저장본이 수동 새로고침 요청보다 먼저 수집됐는지.
     *
     * <p>store_only 모드에서는 이 검사를 하지 않습니다. 외부를 부르지 않는다는
     * 약속이 우선이기 때문입니다(그 모드에서 새로고침은 저장본 재조회입니다).
     */
    private boolean supersededByRefresh(Snapshot snapshot) {
        Instant requestedAt = repository.refreshRequestedAt("global");
        if (requestedAt == null || snapshot.collectedAt() == null) {
            return false;
        }
        return snapshot.collectedAt().isBefore(requestedAt);
    }
}
