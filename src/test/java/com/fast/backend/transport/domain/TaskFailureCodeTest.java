package com.fast.backend.transport.domain;

import com.fast.backend.station.domain.StationMeasurementStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 측정 상태 → 실패 코드 매핑.
 *
 * <p>가장 중요한 규칙은 <b>OK 가 아닌 상태에서는 적재 부적합이 나오면 안 된다</b>는 것이다.
 * dimensions_only/no_detection/unreliable 은 전복·돌출 값이 null 이라 적재 적합 판정이 항상
 * 실패하는데, 그것을 "화물을 다시 쌓으세요"로 안내하면 원인을 정반대로 알려주게 된다.
 */
class TaskFailureCodeTest {

    @Test
    void noDetection_mapsToNoDetectionCode() {
        assertThat(TaskFailureCode.fromMeasurement(StationMeasurementStatus.NO_DETECTION))
                .isEqualTo(TaskFailureCode.MEASUREMENT_NO_DETECTION);
    }

    @Test
    void unreliable_mapsToDistanceUnreliableCode() {
        assertThat(TaskFailureCode.fromMeasurement(StationMeasurementStatus.UNRELIABLE))
                .isEqualTo(TaskFailureCode.MEASUREMENT_DISTANCE_UNRELIABLE);
    }

    @Test
    void dimensionsOnly_mapsToPalletNotDetectedNotPlacementIneligible() {
        assertThat(TaskFailureCode.fromMeasurement(StationMeasurementStatus.DIMENSIONS_ONLY))
                .isEqualTo(TaskFailureCode.MEASUREMENT_PALLET_NOT_DETECTED);
    }

    /** 측정은 정상인데 실패했다면 남은 원인은 적재 부적합뿐이다. */
    @Test
    void okButFailed_mapsToPlacementIneligible() {
        assertThat(TaskFailureCode.fromMeasurement(StationMeasurementStatus.OK))
                .isEqualTo(TaskFailureCode.PLACEMENT_INELIGIBLE);
    }

    /** 측정 결과 자체가 없다 = 응답이 오지 않았다. */
    @Test
    void missingMeasurement_mapsToNoResponse() {
        assertThat(TaskFailureCode.fromMeasurement(null))
                .isEqualTo(TaskFailureCode.MEASUREMENT_NO_RESPONSE);
    }
}
