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

    @Override
    public void update(VehicleLocationSnapshot snapshot) {
        if (snapshot == null || snapshot.vehicleId() == null) {
            return;
        }
        latestByVehicleId.put(snapshot.vehicleId(), snapshot);
    }
}
