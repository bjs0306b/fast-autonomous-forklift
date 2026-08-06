package com.fast.backend.storage.placement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 적재 위치 추천에 쓰는 설정값. 매직 넘버를 코드에 박지 않고
 * {@code storage.placement.*}로 외부화한다.
 *
 * <p>{@code heightClearance}: 화물 높이 위에 추가로 확보해야 하는 현실 스케일 여유 높이(m).
 * 기본값은 0.25m다.
 *
 * <p>{@code palletHeightM}: T-11 팔레트 실물 높이(m, 기본 0.12). <b>화물은 항상 팔레트에 실려 운반되지만
 * {@code station_measurement.cargo_height}에는 팔레트 높이가 들어 있지 않다</b>(화물만의 높이, m).
 * 그래서 적재 높이를 판정할 때 이 값을 <b>정확히 한 번</b> 더한다:
 * {@code requiredHeight = cargoHeight + palletHeightM + heightClearance}. 팔레트 높이를 DB 값에 미리
 * 더해 저장하거나 계산 단계마다 다시 더하면 이중 가산이 되므로 하지 않는다.
 * 환경변수 {@code STORAGE_PLACEMENT_PALLET_HEIGHT_M}로 재정의할 수 있다.
 *
 * <p>{@code maxOverhangRatioExclusive}: 적재를 허용하는 화물 돌출률 상한(무차원, 기본 0.05).
 * 이름의 <b>Exclusive</b>가 경계 처리를 말한다 — {@code overhangRatio < limit}만 허용하므로
 * 값이 정확히 0.05면 <b>차단</b>이다. 코드에 0.05를 박지 않고 여기서 읽는다.
 * 환경변수 {@code STORAGE_PLACEMENT_MAX_OVERHANG_RATIO_EXCLUSIVE}로 재정의할 수 있다.
 */
@ConfigurationProperties(prefix = "storage.placement")
public record PlacementProperties(
        Double heightClearance,
        Double palletHeightM,
        Double maxOverhangRatioExclusive,
        Double widthClearance
) {

    public static final double DEFAULT_HEIGHT_CLEARANCE_M = 0.25;

    /** 설정을 비워 두었을 때 쓰는 T-11 팔레트 높이(m). */
    public static final double DEFAULT_PALLET_HEIGHT_M = 0.12;

    /** 돌출률 상한 기본값(무차원, 경계 미포함). `docs/backend-api/optimal-placement.md` 7.1 근거. */
    public static final double DEFAULT_MAX_OVERHANG_RATIO_EXCLUSIVE = 0.05;

    /**
     * 폭 여유(m). 화물 폭이 슬롯 가용 폭보다 이만큼은 작아야 한다.
     *
     * <p>높이 여유({@link #DEFAULT_HEIGHT_CLEARANCE_M} 0.25m)보다 작게 잡았다 — 세로는 포크를
     * 올리다 부딪히면 화물이 떨어지지만, 가로는 지게차가 정면으로 밀어 넣는 방향이라
     * 접촉 위험이 낮다. 실측으로 조정할 것.
     */
    public static final double DEFAULT_WIDTH_CLEARANCE_M = 0.10;

    /**
     * 돌출률 상한이 넘을 수 없는 값. 1.0은 화물이 파렛트 폭만큼 통째로 벗어난 상태라 상한으로는
     * 의미가 없다 — 설정 오타(예: 5 를 "5%" 로 착각)를 기동 시점에 잡으려는 방어값이다.
     */
    private static final double MAX_SANE_OVERHANG_LIMIT = 1.0;

    public PlacementProperties {
        if (heightClearance == null) {
            heightClearance = DEFAULT_HEIGHT_CLEARANCE_M;
        }
        if (!Double.isFinite(heightClearance) || heightClearance < 0) {
            throw new IllegalArgumentException(
                    "storage.placement.height-clearance must be a finite value greater than or equal to 0: "
                            + heightClearance);
        }
        if (palletHeightM == null) {
            palletHeightM = DEFAULT_PALLET_HEIGHT_M;
        }
        if (!Double.isFinite(palletHeightM) || palletHeightM <= 0) {
            throw new IllegalArgumentException(
                    "storage.placement.pallet-height-m must be a finite value greater than 0: " + palletHeightM);
        }
        if (maxOverhangRatioExclusive == null) {
            maxOverhangRatioExclusive = DEFAULT_MAX_OVERHANG_RATIO_EXCLUSIVE;
        }
        if (!Double.isFinite(maxOverhangRatioExclusive) || maxOverhangRatioExclusive <= 0
                || maxOverhangRatioExclusive > MAX_SANE_OVERHANG_LIMIT) {
            throw new IllegalArgumentException(
                    "storage.placement.max-overhang-ratio-exclusive must be a finite value in (0, "
                            + MAX_SANE_OVERHANG_LIMIT + "]: " + maxOverhangRatioExclusive);
        }
        if (widthClearance == null) {
            widthClearance = DEFAULT_WIDTH_CLEARANCE_M;
        }
        if (!Double.isFinite(widthClearance) || widthClearance < 0) {
            throw new IllegalArgumentException(
                    "storage.placement.width-clearance must be a finite value greater than or equal to 0: "
                            + widthClearance);
        }
    }
}
