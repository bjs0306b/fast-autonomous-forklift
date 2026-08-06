package com.fast.backend.vehicle.location;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LatestVehicleLocationProvider}의 인메모리 구현(prompt47.md 3-3).
 * {@link ConcurrentHashMap}으로 vehicleId별 최신 스냅샷 1개만 유지한다 — 이력 누적 없음, DB 저장 없음,
 * 애플리케이션 재시작 시 소실 허용.
 */
@Component
public class InMemoryLatestVehicleLocationProvider implements LatestVehicleLocationProvider {

    private final ConcurrentHashMap<String, VehicleLocationSnapshot> latestByVehicleId = new ConcurrentHashMap<>();

    @Override
    public Optional<VehicleLocationSnapshot> findLatest(String vehicleId) {
        return Optional.ofNullable(latestByVehicleId.get(vehicleId));
    }

    @Override
    public List<VehicleLocationSnapshot> findAllLatest() {
        return List.copyOf(latestByVehicleId.values());
    }

    /**
     * vehicleId별 최신값을 덮어쓴다. 단, <b>오래된 메시지가 최신 위치를 덮어쓰지 않도록</b> messageAt을
     * 비교한다(prompt50.md 7장): 기존값이 있고 두 messageAt이 모두 있으면 새 메시지가 더 최신일 때만 교체한다.
     * 비교할 messageAt이 없으면(둘 중 하나라도 null) 최신 수신을 신뢰해 그대로 덮어쓴다.
     * {@link ConcurrentHashMap#compute}로 원자적으로 수행해 동시 수신에도 안전하다.
     */
    @Override
    public void update(VehicleLocationSnapshot snapshot) {
        if (snapshot == null || snapshot.vehicleId() == null) {
            return;
        }
        latestByVehicleId.compute(snapshot.vehicleId(), (id, existing) -> {
            if (existing != null && existing.messageAt() != null && snapshot.messageAt() != null
                    && !snapshot.messageAt().isAfter(existing.messageAt())) {
                return existing; // 오래되었거나 동일한 시각 → 유지
            }
            return snapshot;
        });
    }

    /**
     * 보관 중인 최신 위치를 모두 비운다.
     *
     * <p>이 저장소는 스프링 컨텍스트 수명 동안 살아 있고 <b>트랜잭션 롤백으로 되돌아가지 않는다.</b>
     * 그래서 통합 테스트가 같은 차량 ID 로 위치를 흘리면 앞 테스트가 남긴 값이 뒤 테스트의 판정을
     * 바꾼다(더 최신 messageAt 이 남아 있으면 새 위치가 아예 반영되지 않는다). 테스트가 실행 순서에
     * 의존하지 않도록 초기화 지점을 열어 둔다. 운영 코드에서는 호출하지 않는다.
     */
    public void clear() {
        latestByVehicleId.clear();
    }
}
