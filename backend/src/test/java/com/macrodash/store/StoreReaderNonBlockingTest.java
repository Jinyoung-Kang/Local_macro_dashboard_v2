package com.macrodash.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.macrodash.collector.CollectorClient;
import com.macrodash.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "화면은 수집을 기다리지 않는다"(README §4-5)를 코드로 고정합니다.
 *
 * <p>예전에는 저장본이 조금 오래되기만 해도 수집이 끝날 때까지 붙잡고
 * 있었습니다. 실제 로그에서 페이지 한 번 여는 데 이만큼 멈췄습니다.
 *
 * <pre>
 * 07:11:53 sec_13f 시작 → 07:12:25 완료 31.81s   ← 31.8초 대기
 * 07:11:27 fred_series 시작 → 07:11:39 완료 11.56s ← 11.5초 대기
 * </pre>
 */
class StoreReaderNonBlockingTest {

    /** runTask(name, wait) 호출을 기록하는 가짜 수집기. */
    private static class RecordingCollector extends CollectorClient {
        final List<Boolean> waits = new CopyOnWriteArrayList<>();

        RecordingCollector() {
            super(properties());
        }

        @Override
        public Optional<JsonNode> runTask(String taskName, boolean wait) {
            waits.add(wait);
            return Optional.empty();
        }
    }

    private static AppProperties properties() {
        AppProperties props = new AppProperties();
        props.setCollectorUrl("http://localhost:1");
        props.setCollectorTimeoutSeconds(1);
        return props;
    }

    private Snapshot staleSnapshot() {
        return new Snapshot("test.dataset", null, "json", "ok", null,
                Instant.now().minus(Duration.ofDays(3)));
    }

    private StoreReader reader(StoreRepository repository, CollectorClient collector) {
        AppProperties props = properties();
        props.setReadMode("auto");
        return new StoreReader(repository, collector, props);
    }

    @Test
    @DisplayName("저장본이 오래됐어도 수집을 기다리지 않고 바로 돌려준다")
    void staleSnapshotIsReturnedWithoutWaiting() {
        StoreRepository repository = mock(StoreRepository.class);
        Snapshot stale = staleSnapshot();
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(stale));

        RecordingCollector collector = new RecordingCollector();
        Optional<Snapshot> result = reader(repository, collector).read("test.dataset", 60, "some_task");

        assertThat(result).containsSame(stale);
        assertThat(collector.waits)
                .as("보여 줄 값이 있으면 wait=false로 요청해야 합니다")
                .containsExactly(false);
    }

    @Test
    @DisplayName("저장본이 아예 없으면 기다린다 — 빈 화면보다는 낫다")
    void missingSnapshotStillWaits() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.empty());

        RecordingCollector collector = new RecordingCollector();
        reader(repository, collector).read("test.dataset", 60, "some_task");

        assertThat(collector.waits)
                .as("보여 줄 값이 없을 때는 기다려야 합니다")
                .containsExactly(true);
    }

    @Test
    @DisplayName("같은 태스크를 짧은 시간에 반복 요청하지 않는다")
    void repeatedTriggersAreThrottled() {
        // 화면 하나가 스냅샷을 병렬로 읽으면 같은 태스크 요청이 그만큼 나갑니다.
        // 실제 로그에서 한 번의 페이지 로드에 fred_series 요청이 7건이었습니다.
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(staleSnapshot()));

        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector);

        for (int i = 0; i < 7; i++) {
            reader.read("fred.series.DGS10", 60, "fred_series");
        }

        assertThat(collector.waits)
                .as("7번 읽어도 수집 요청은 한 번이어야 합니다")
                .hasSize(1);
    }

    @Test
    @DisplayName("다른 태스크는 서로를 막지 않는다")
    void differentTasksAreNotThrottledTogether() {
        StoreRepository repository = mock(StoreRepository.class);
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(staleSnapshot()));

        RecordingCollector collector = new RecordingCollector();
        StoreReader reader = reader(repository, collector);

        reader.read("fred.series.DGS10", 60, "fred_series");
        reader.read("ticker.VIX.5y", 60, "volatility_history");

        assertThat(collector.waits).hasSize(2);
    }

    @Test
    @DisplayName("신선하면 수집을 요청하지 않는다")
    void freshSnapshotTriggersNothing() {
        StoreRepository repository = mock(StoreRepository.class);
        Snapshot fresh = new Snapshot("test.dataset", null, "json", "ok", null, Instant.now());
        when(repository.readSnapshot(anyString())).thenReturn(Optional.of(fresh));

        RecordingCollector collector = new RecordingCollector();
        reader(repository, collector).read("test.dataset", 3600, "some_task");

        assertThat(collector.waits).isEmpty();
    }
}
