package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.config.VehicleProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 외부 시스템의 차량 ID 를 DB 기준 {@code vehicle_id} 로 정규화한다.
 *
 * <p>Isaac Sim 은 {@code sim01}, DB 는 {@code SIM-F01} 을 쓴다. 이 대응을 코드 여러 곳에 {@code if} 로
 * 두면 차량이 늘 때마다 코드를 고쳐야 하므로 설정({@code vehicle.id-aliases}) 한 곳에만 둔다.
 *
 * <p><b>대소문자를 무시해 비교한다.</b> 발신 측이 {@code sim01} 을 {@code SIM01} 로 바꿔 보내는 것은
 * 흔한 일인데, 그때 차량이 통째로 사라지는 것(미등록 폐기)보다 같은 차량으로 인식하는 편이 안전하다.
 * 반환값은 설정에 적힌 <b>그대로</b>이므로 DB 조회에는 영향이 없다.
 *
 * <p>별칭에 없는 ID 는 <b>그대로 돌려준다.</b> 이미 DB 기준 ID 로 발행하는 기존 ROS2 경로
 * ({@code forklift/SIM-F01/location})가 이 리졸버를 거쳐도 값이 바뀌지 않아야 하기 때문이다.
 * 최종적으로 그 ID 가 DB 에 없으면 기존 정책대로 위치 공통 처리기가 폐기한다.
 */
@Component
public class VehicleIdAliasResolver {

    private final Map<String, String> aliasesByLowerKey;
    private final Map<String, String> externalIdsByLowerDbId;

    public VehicleIdAliasResolver(VehicleProperties properties) {
        Map<String, String> resolved = new HashMap<>();
        properties.idAliases().forEach((rawKey, value) -> {
            if (rawKey != null && !rawKey.isBlank() && value != null && !value.isBlank()) {
                resolved.put(rawKey.trim().toLowerCase(Locale.ROOT), value.trim());
            }
        });
        this.aliasesByLowerKey = Map.copyOf(resolved);

        Map<String, String> reversed = new HashMap<>();
        resolved.forEach((externalId, dbId) -> reversed.merge(
                dbId.toLowerCase(Locale.ROOT), externalId,
                (left, right) -> left.compareToIgnoreCase(right) <= 0 ? left : right));
        this.externalIdsByLowerDbId = Map.copyOf(reversed);
    }

    /**
     * @return DB 기준 ID. 별칭이 없으면 입력값을 다듬어 그대로 반환한다. 입력이 비어 있으면 빈 값
     */
    public Optional<String> resolve(String externalVehicleId) {
        if (externalVehicleId == null || externalVehicleId.isBlank()) {
            return Optional.empty();
        }
        String trimmed = externalVehicleId.trim();
        return Optional.of(aliasesByLowerKey.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed));
    }

    /**
     * DB 기준 ID를 Isaac MQTT 토픽에서 사용하는 외부 ID로 되돌린다.
     * 별칭이 없는 자동 등록 차량(sim03 등)은 입력 ID를 그대로 사용한다.
     */
    public Optional<String> resolveExternal(String databaseVehicleId) {
        if (databaseVehicleId == null || databaseVehicleId.isBlank()) {
            return Optional.empty();
        }
        String trimmed = databaseVehicleId.trim();
        return Optional.of(externalIdsByLowerDbId.getOrDefault(
                trimmed.toLowerCase(Locale.ROOT), trimmed));
    }

    /** 이 ID 가 별칭표에 실제로 등록돼 있는지(로그·진단용). */
    public boolean hasAlias(String externalVehicleId) {
        return externalVehicleId != null
                && aliasesByLowerKey.containsKey(externalVehicleId.trim().toLowerCase(Locale.ROOT));
    }
}
