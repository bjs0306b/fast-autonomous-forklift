package com.fast.backend.vehicle.service;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.config.VehicleProperties;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * MQTT 로 처음 관측된 차량을 등록해, 브로커가 보내는 <b>차량 수만큼</b> 관제 화면에 뜨게 한다.
 *
 * <p><b>왜 필요한가.</b> 위치·상태 처리기는 {@code vehicle} 테이블에 없는 vehicleId 를 전부 폐기한다
 * ({@link com.fast.backend.vehicle.location.VehicleLocationIngestionService}). 그래서 시뮬레이터가
 * 두 대를 발행해도 DB 시드에 있는 한 대만 화면에 남았다. 시드에 차량 ID 를 미리 적어 두는 방식은
 * 차량이 늘 때마다 SQL 을 고쳐야 하고, 그 값이 코드·설정·DB 세 곳으로 흩어진다.
 *
 * <p><b>차량 ID 를 만들어 내지 않는다.</b> 여기서 쓰는 ID 는 이미 정규화를 마친 DB 기준 ID 다
 * ({@link VehicleIdAliasResolver}). 문자열을 치환하거나 접두어를 붙이지 않으므로, 서로 다른 물리
 * 차량이 한 행으로 합쳐지지 않는다. 표시 이름도 ID 를 그대로 쓴다 — 운영자가 나중에 바꿀 수 있는
 * 값이라, 여기서 그럴듯한 한글 이름을 지어내면 실제 장비명과 어긋난 채 굳는다.
 *
 * <p><b>플래그로 끌 수 있다.</b> {@code vehicle.auto-registration-enabled=false} 면 등록하지 않고
 * 기존 동작(미등록 차량 폐기)을 그대로 유지한다.
 */
@Component
public class VehicleAutoRegistrar {

    private static final Logger log = LoggerFactory.getLogger(VehicleAutoRegistrar.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper statusMapper;
    private final boolean enabled;

    public VehicleAutoRegistrar(
            VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper statusMapper,
            VehicleProperties properties) {
        this.vehicleMapper = vehicleMapper;
        this.statusMapper = statusMapper;
        this.enabled = properties.autoRegistrationEnabled();
    }

    /**
     * 이 차량이 처리 가능한 상태인지 보장한다.
     *
     * <p><b>호출부의 트랜잭션에 참여한다</b>({@code REQUIRED}). 별도 트랜잭션으로 떼어내면 바깥
     * 트랜잭션이 아직 커밋하지 않은 차량 행이 여기서 안 보여, 방금 등록된 차량을 미등록으로 판정하고
     * 메시지를 버린다. 두 MQTT 경로 모두 이 메서드를 트랜잭션 밖에서 부르므로 대개 여기가 경계다.
     *
     * @return 이 차량으로 계속 처리해도 되면 true. 미등록이고 자동 등록이 꺼져 있으면 false
     */
    @Transactional
    public boolean ensureRegistered(String vehicleId, String source) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return false;
        }
        if (vehicleMapper.existsByVehicleId(vehicleId)) {
            return true;
        }
        if (!enabled) {
            return false;
        }

        LocalDateTime now = CommunicationTime.nowLocal();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);

        try {
            vehicleMapper.insert(vehicle);
        } catch (DuplicateKeyException e) {
            // 같은 차량의 첫 메시지가 두 건 동시에 들어온 경우다. 먼저 넣은 쪽이 이겼을 뿐이므로
            // 실패가 아니다 — 그대로 처리를 이어 간다.
            log.debug("Vehicle already registered by a concurrent message: vehicleId={}", vehicleId);
            return true;
        }

        VehicleCurrentStatus initialStatus = new VehicleCurrentStatus();
        initialStatus.setVehicleId(vehicleId);
        initialStatus.setStatus(VehicleStatus.UNKNOWN);
        initialStatus.setReceivedAt(now);
        statusMapper.upsert(initialStatus);

        log.info("Vehicle auto-registered from MQTT: vehicleId={}, source={}", vehicleId, source);
        return true;
    }
}
