package com.fast.backend.loadsafety.domain;

/**
 * 적재 안전 데이터의 출처(prompt63.md 5장 {@code source}).
 *
 * <p>같은 차량에 대해 비전(카메라)과 센서가 서로 다른 주기로 값을 보낼 수 있으므로, 화면이 "무엇이
 * 준 값인지"를 구분할 수 있도록 보존한다. {@link LoadSafetyRiskLevel}과 마찬가지로 <b>모르는 값은
 * 흡수하지 않고</b> {@link #UNKNOWN}으로 둔다.
 */
public enum LoadSafetySource {

    /** 카메라·비전 노드(YOLO/Depth 등). */
    VISION,
    /** 차량 탑재 센서(IMU·로드셀 등). */
    SENSOR,
    /** ROS2 노드가 취합해 보낸 값. */
    ROS2,
    /** 출처 미상 또는 이 백엔드가 모르는 값. */
    UNKNOWN;

    public static LoadSafetySource fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return LoadSafetySource.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
