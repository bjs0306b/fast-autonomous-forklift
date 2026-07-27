package com.fast.backend.transport.assign;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 가장 가까운 IDLE 차량을 고르는 자동 배정 구현 <b>초안</b>(prompt46.md 12장).
 *
 * <p><b>제한사항(TODO)</b>: 이 구현은 후보의 {@code positionX}/{@code positionY}가 채워져 있어야 거리
 * 계산을 할 수 있다. 그러나 현재 프로젝트는 MQTT 위치 메시지를 {@code vehicle_current_status}에 저장하지
 * 않아(prompt44.md 확인) 위치가 대부분 null이다. 따라서:
 * <ul>
 *   <li>위치가 없는 후보(positionX/Y null)는 거리 계산 불가라 <b>후보에서 제외</b>한다.</li>
 *   <li>결과적으로 위치 저장이 확정되기 전에는 이 selector가 빈 결과를 낼 수 있다 — 그 경우 자동 배정을
 *       강행하지 않고 수동 배정을 쓰도록 호출 Service가 판단한다.</li>
 * </ul>
 * 위치 저장 정책이 확정되면(전체 팀) 이 클래스를 그대로 활성화하면 된다 — 인터페이스·필터·정렬 구조는
 * 확정 규격에 맞춰 이미 갖춰져 있다.
 *
 * <p><b>필터</b>: 온라인 && 상태 IDLE && 활성 작업 없음 && 위치 존재. (IDLE 조건이 ERROR/OFFLINE 차량을
 * 자연히 제외한다.)
 *
 * <p><b>정렬(tie-break)</b>: prompt47.md 3-2 권장값 — 거리 오름차순 → 먼저 IDLE이 된 차량(idleSince
 * 이른 순) → 상태 timestamp 최신 순 → vehicleId 오름차순. vehicleId를 앞에 두면 고유값이라 뒤 기준이
 * 무효화되므로 최후의 결정자로 둔다(팀 정책 미명시라 권장값 채택, 기존 테스트는 동일 거리 vehicleId 강제 안 함).
 */
public class NearestIdleVehicleSelector implements AutomaticVehicleSelector {

    @Override
    public Optional<VehicleCandidate> select(
            double pickupX, double pickupY, List<VehicleCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(this::isSelectable)
                .min(comparator(pickupX, pickupY));
    }

    private boolean isSelectable(VehicleCandidate candidate) {
        return candidate != null
                && candidate.online()
                && candidate.status() == VehicleStatus.IDLE
                && !candidate.hasActiveTask()
                && candidate.positionX() != null
                && candidate.positionY() != null;
    }

    private Comparator<VehicleCandidate> comparator(double pickupX, double pickupY) {
        return Comparator
                .comparingDouble((VehicleCandidate v) -> distance(v, pickupX, pickupY))
                .thenComparing(VehicleCandidate::idleSince,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VehicleCandidate::statusTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(VehicleCandidate::vehicleId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private double distance(VehicleCandidate candidate, double pickupX, double pickupY) {
        double dx = candidate.positionX() - pickupX;
        double dy = candidate.positionY() - pickupY;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
