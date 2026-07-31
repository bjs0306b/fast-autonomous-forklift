package com.fast.backend.storage.placement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 적재 위치 추천에 쓰는 설정값(prompt46.md 6장). 매직 넘버를 코드에 박지 않고
 * {@code storage.placement.*}로 외부화한다.
 *
 * <p>{@code heightClearance}: 화물 높이 위에 추가로 확보해야 하는 여유 높이(m). 값을 지정하지 않으면
 * 0(여유 없음)이며, 이는 prompt46.md 6장이 허용하는 기본값이다. 실제 운영 여유값은 팀 협의 대상이다(20장).
 *
 * <p>{@code palletHeightM}: T-11 팔레트 실물 높이(m, 기본 0.12). <b>화물은 항상 팔레트에 실려 운반되지만
 * {@code station_measurement.cargo_height}에는 팔레트 높이가 들어 있지 않다</b>(화물만의 높이, m).
 * 그래서 적재 높이를 판정할 때 이 값을 <b>정확히 한 번</b> 더한다:
 * {@code requiredHeight = cargoHeight + palletHeightM + heightClearance}. 팔레트 높이를 DB 값에 미리
 * 더해 저장하거나 계산 단계마다 다시 더하면 이중 가산이 되므로 하지 않는다(prompt95 3장).
 * 환경변수 {@code STORAGE_PLACEMENT_PALLET_HEIGHT_M}로 재정의할 수 있다.
 */
@ConfigurationProperties(prefix = "storage.placement")
public record PlacementProperties(
        double heightClearance,
        Double palletHeightM
) {

    /** 설정을 비워 두었을 때 쓰는 T-11 팔레트 높이(m). `docs/backend-api/optimal-placement.md` 6.2 근거. */
    public static final double DEFAULT_PALLET_HEIGHT_M = 0.12;

    public PlacementProperties {
        if (palletHeightM == null) {
            palletHeightM = DEFAULT_PALLET_HEIGHT_M;
        }
        if (!Double.isFinite(palletHeightM) || palletHeightM <= 0) {
            throw new IllegalArgumentException(
                    "storage.placement.pallet-height-m must be a finite value greater than 0: " + palletHeightM);
        }
    }
}
