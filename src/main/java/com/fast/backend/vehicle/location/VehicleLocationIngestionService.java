package com.fast.backend.vehicle.location;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.service.VehicleAutoRegistrar;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 위치 한 건을 <b>DB · 인메모리 · WebSocket</b> 세 곳에 반영하는 공통 처리기.
 *
 * <p>발신 계약이 늘어나도(ROS2 {@code forklift/+/location}, Isaac {@code fast/v1/vehicle/+/telemetry}, …)
 * 이 세 갈래는 한 벌만 존재해야 한다. 계약마다 복사하면 "미니맵에는 뜨는데 대시보드에는 안 뜬다" 같은
 * 반쪽 반영이 생기고, 구식 메시지 차단 규칙이 경로마다 달라진다.
 *
 * <p>각 어댑터의 책임은 <b>자기 스키마를 이 입력 형태로 바꾸는 것까지</b>다 — ID 정규화, 단위 변환
 * (radian → degree 등), 스키마별 검증. 그 뒤는 전부 여기서 같은 규칙으로 처리한다.
 */
@Service
public class VehicleLocationIngestionService {

    private static final Logger log = LoggerFactory.getLogger(VehicleLocationIngestionService.class);

    /** {@code position.frameId} 가 없을 때의 기본 좌표계(prompt32.md 1장 5번 확정). */
    public static final String DEFAULT_FRAME_ID = "map";

    private final VehicleAutoRegistrar autoRegistrar;
    private final VehicleCurrentStatusMapper statusMapper;
    private final LatestVehicleLocationProvider latestVehicleLocationProvider;
    private final VehicleWebSocketBroadcaster broadcaster;

    public VehicleLocationIngestionService(
            VehicleAutoRegistrar autoRegistrar,
            VehicleCurrentStatusMapper statusMapper,
            LatestVehicleLocationProvider latestVehicleLocationProvider,
            VehicleWebSocketBroadcaster broadcaster) {
        this.autoRegistrar = autoRegistrar;
        this.statusMapper = statusMapper;
        this.latestVehicleLocationProvider = latestVehicleLocationProvider;
        this.broadcaster = broadcaster;
    }

    /**
     * 위치를 반영한다.
     *
     * @param source 로그에서 어느 계약으로 들어온 값인지 구분하기 위한 이름(예: {@code ros2-location})
     * @return 실제로 반영·브로드캐스트됐으면 true. 미등록 차량이거나 구식 메시지면 false
     */
    public boolean ingest(VehicleLocationIngestion request, String source) {
        String vehicleId = request.vehicleId();

        // 처음 보는 차량이면 여기서 등록된다(vehicle.auto-registration-enabled). 자동 등록이 꺼져
        // 있으면 예전처럼 폐기한다.
        if (!autoRegistrar.ensureRegistered(vehicleId, source)) {
            // 폐기 사유와 messageAt을 함께 남긴다(prompt73 3.3장) — vehicleId만으로는 등록 누락인지
            // 다른 원인인지 구분되지 않는다.
            log.warn("Vehicle location discarded: reason=vehicle not registered, source={}, "
                            + "vehicleId={}, messageAt={}",
                    source, vehicleId, request.messageAt());
            return false;
        }

        OffsetDateTime receivedAt = CommunicationTime.nowOffset();
        String frameId = normalizeFrameId(request.frameId());
        Double heading = normalizeHeading(request.heading());

        int updated = statusMapper.updateLocationIfNewer(
                vehicleId,
                request.x(),
                request.y(),
                frameId,
                heading,
                request.speed(),
                CommunicationTime.toLocal(request.messageAt()),
                CommunicationTime.toLocal(receivedAt));
        if (updated <= 0) {
            // 이미 더 최신 위치가 저장돼 있다. 구식 메시지가 최신 좌표를 되돌리지 않도록 여기서 멈춘다.
            log.debug("Stale vehicle location ignored: source={}, vehicleId={}, messageAt={}",
                    source, vehicleId, request.messageAt());
            return false;
        }

        latestVehicleLocationProvider.update(new VehicleLocationSnapshot(
                vehicleId, request.x(), request.y(), heading, request.speed(), frameId,
                request.messageAt(), receivedAt, request.forkHeight(), request.battery(),
                request.reportedCargoId(), request.reportedTaskId(), request.reportedLoaded(),
                request.reportedCargoHeight()));

        broadcaster.broadcastLocation(vehicleId, new VehicleLocationEventData(
                vehicleId,
                request.reportedStatus() == null ? VehicleStatus.UNKNOWN : request.reportedStatus(),
                new VehicleLocationEventData.Position(request.x(), request.y(), frameId),
                heading,
                request.quaternion(),
                request.speed(),
                request.messageAt(),
                receivedAt,
                request.forkHeight(),
                request.battery(),
                request.reportedCargoId(),
                request.reportedTaskId(),
                request.reportedLoaded(),
                request.reportedCargoHeight()), request.messageAt());
        return true;
    }

    /** 생략(null/빈 값)은 기본값 {@code map}으로 채운다. 허용 목록 검증은 각 어댑터가 미리 한다. */
    public static String normalizeFrameId(String frameId) {
        return (frameId == null || frameId.isBlank()) ? DEFAULT_FRAME_ID : frameId.trim();
    }

    /**
     * heading(degree)을 [0, 360) 범위로 정규화한다(예: -90 → 270, 450 → 90, prompt24.md 5장).
     *
     * <p>프론트는 이 값을 변환 없이 그대로 표시하므로, 여기서 어긋나면 화면의 방향이 곧바로 틀어진다.
     */
    public static Double normalizeHeading(Double heading) {
        if (heading == null) {
            return null;
        }
        double normalized = heading % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return normalized;
    }
}
