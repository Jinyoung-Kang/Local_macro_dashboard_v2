package com.macrodash.store;

import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

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

        log.info("저장본이 오래됐습니다({}). 수집기에 '{}' 태스크를 요청합니다.", name, taskName);
        Optional<?> triggered = collector.runTask(taskName);

        Optional<Snapshot> refreshed = repository.readSnapshot(name);
        if (refreshed.isPresent()) {
            return refreshed;
        }

        if (triggered.isEmpty()) {
            log.warn("수집 요청 실패({}). 남아 있는 저장본으로 대체합니다.", name);
        }
        return stored;
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
