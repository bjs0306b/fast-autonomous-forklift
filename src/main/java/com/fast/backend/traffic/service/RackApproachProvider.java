package com.fast.backend.traffic.service;

import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 랙 코드 → 접근점 좌표. {@code storage_slot} 을 한 번 읽어 캐시한다.
 *
 * <p><b>왜 캐시하는가.</b> 주기 tick 은 0.5 초마다 돌고 차량마다 랙을 묻는다. 좌표는 시드로
 * 한 번 넣고 바뀌지 않는 값이라 매번 DB 를 칠 이유가 없다. 좌표를 바꿔야 하면 재기동한다 —
 * 운행 중에 목적지가 바뀌는 편이 더 위험하다.
 *
 * <p>좌표는 <b>시뮬 좌표계</b>이고 {@code destination_heading} 은 degree 다(스키마 기준).
 * 발행할 때 라디안으로 바꾼다.
 */
@Component
public class RackApproachProvider {

    private static final Logger log = LoggerFactory.getLogger(RackApproachProvider.class);

    private final StorageSlotMapper storageSlotMapper;
    private final Map<String, Approach> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public RackApproachProvider(StorageSlotMapper storageSlotMapper) {
        this.storageSlotMapper = storageSlotMapper;
    }

    /**
     * 랙 접근점. 없는 코드면 빈 값.
     *
     * <p>못 찾으면 <b>목표를 보내지 않는다</b> — 좌표를 짐작해 보내면 차량이 엉뚱한 곳으로 간다.
     */
    public Optional<Approach> find(String slotCode) {
        if (slotCode == null || slotCode.isBlank()) {
            return Optional.empty();
        }
        loadIfNeeded();
        return Optional.ofNullable(cache.get(slotCode));
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                for (StorageSlot slot : storageSlotMapper.findAll()) {
                    // 좌표 컬럼은 NOT NULL 이라 원시 타입이다. 유한성만 확인한다 —
                    // NaN 이 섞이면 차량이 목표를 못 풀고 그 자리에 붙는다.
                    if (!Double.isFinite(slot.getDestinationX())
                            || !Double.isFinite(slot.getDestinationY())) {
                        log.warn("랙 접근점 좌표가 유효하지 않아 건너뜀: slot={}", slot.getSlotCode());
                        continue;
                    }
                    cache.put(slot.getSlotCode(), new Approach(
                            slot.getSlotCode(),
                            slot.getDestinationX(),
                            slot.getDestinationY(),
                            Math.toRadians(slot.getDestinationHeading()),
                            slot.getForkHeight()));
                }
                loaded = true;
                log.info("랙 접근점 {}개를 읽었다.", cache.size());
            } catch (RuntimeException e) {
                // 다음 tick 에 다시 시도한다. loaded 를 세우지 않는 것이 재시도의 전부다.
                log.error("랙 접근점 조회 실패(다음 tick 에 재시도): {}", e.getMessage());
            }
        }
    }

    /**
     * 랙 접근점 한 곳.
     *
     * @param yawRad     진입 방향(라디안). DB 는 degree 로 들고 있어 여기서 변환된 값이다
     * @param forkHeight 포크 목표 높이(시뮬 단위). {@code task.dropoff} 로 그대로 나간다
     */
    public record Approach(String slotCode, double x, double y, double yawRad, double forkHeight) {
    }
}
