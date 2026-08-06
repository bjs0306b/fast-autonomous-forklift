package com.fast.backend.traffic.service;

import com.fast.backend.traffic.config.TrafficControlProperties;
import com.fast.backend.traffic.domain.VehicleMotion;
import com.fast.backend.traffic.domain.WorkZone;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 선반 작업 구역의 점유 상태를 <b>메모리에서</b> 관리한다 (FR-502-1a).
 *
 * <p>DB 에 쓰지 않는 이유: 점유는 <b>지금 이 순간의 판단</b>이고 tick 마다 바뀐다. 매 tick DB 를
 * 오가면 비용만 늘고, 백엔드가 재시작되면 어차피 차량이 다시 상태를 보내오므로 몇 tick 안에
 * 복원된다. 영속이 필요한 것은 "무슨 일이 있었는가"(정지/재개 이벤트)이지 순간 점유가 아니다.
 *
 * <p><b>구역 좌표는 {@code storage_slot} 의 접근 좌표를 쓴다.</b> 좌표를 이 클래스가 따로 갖고
 * 있으면 선반이 바뀔 때 두 곳을 고쳐야 하므로, 원본은 DB 한 곳에 둔다.
 */
@Component
public class WorkZoneRegistry implements WorkZoneProvider {

    private static final Logger log = LoggerFactory.getLogger(WorkZoneRegistry.class);

    private final StorageSlotMapper storageSlotMapper;
    private final TrafficControlProperties properties;

    /** slotCode → 구역. 점유자는 tick 마다 갱신된다. */
    private final Map<String, WorkZone> zones = new ConcurrentHashMap<>();

    public WorkZoneRegistry(StorageSlotMapper storageSlotMapper, TrafficControlProperties properties) {
        this.storageSlotMapper = storageSlotMapper;
        this.properties = properties;
    }

    /**
     * 차량 상태를 보고 점유를 다시 계산한다.
     *
     * <p>점유 조건은 <b>"작업 중이면서 그 선반 구역 안에 있음"</b> 둘 다다. 위치만 보면 지나가는
     * 차량이 구역을 잠그고, 상태만 보면 엉뚱한 곳에서 하역하는 차량이 남의 선반을 잠근다.
     *
     * <p>점유는 <b>매 tick 새로 계산</b>한다(누적하지 않는다). 차량이 작업을 끝내고 구역을 벗어나면
     * 다음 tick 에 자동으로 풀린다 — 별도의 "해제" 신호를 기다리지 않으므로, 해제 메시지가 유실돼
     * 구역이 영영 잠기는 상황이 생기지 않는다.
     */
    @Override
    public void refresh(Collection<VehicleMotion> motions) {
        loadZonesIfNeeded();
        for (Map.Entry<String, WorkZone> entry : zones.entrySet()) {
            WorkZone zone = entry.getValue();
            String occupant = findOccupant(zone, motions);
            if (occupant == null) {
                if (zone.isOccupied()) {
                    log.info("작업 구역 해제: slot={}, 이전 점유자={}", zone.slotCode(), zone.occupiedBy());
                    entry.setValue(zone.release());
                }
            } else if (!occupant.equals(zone.occupiedBy())) {
                log.info("작업 구역 점유: slot={}, 점유자={}", zone.slotCode(), occupant);
                entry.setValue(zone.occupy(occupant));
            }
        }
    }

    /** 현재 구역 목록(점유 상태 포함). */
    @Override
    public List<WorkZone> zones() {
        return List.copyOf(zones.values());
    }

    private String findOccupant(WorkZone zone, Collection<VehicleMotion> motions) {
        for (VehicleMotion motion : motions) {
            if (motion.isWorking() && zone.contains(motion.x(), motion.y())) {
                return motion.vehicleId();
            }
        }
        return null;
    }

    /**
     * 선반 좌표를 처음 한 번 읽어 온다.
     *
     * <p>매 tick DB 를 읽지 않는 이유는 선반 좌표가 정적이기 때문이다. 선반을 추가·이동했다면
     * 백엔드를 재시작해야 반영된다 — 관제 tick 마다 조회하는 비용보다 이 제약이 낫다고 봤다.
     */
    private void loadZonesIfNeeded() {
        if (!zones.isEmpty()) {
            return;
        }
        List<StorageSlot> slots = new ArrayList<>(storageSlotMapper.findAll());
        for (StorageSlot slot : slots) {
            zones.put(slot.getSlotCode(), new WorkZone(
                    slot.getSlotCode(),
                    slot.getDestinationX(),
                    slot.getDestinationY(),
                    properties.workZoneRadiusM(),
                    null));
        }
        log.info("작업 구역 {}개 적재: radius={}m", zones.size(), properties.workZoneRadiusM());
    }
}
