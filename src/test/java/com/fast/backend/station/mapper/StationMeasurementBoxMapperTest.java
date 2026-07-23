package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementBox;
import com.fast.backend.station.domain.StationMeasurementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * station_measurement_box insert/조회, box_order 정렬, nullable bbox 저장이 실제 H2에서 동작하는지
 * 검증한다(prompt16.md 11단계). 자식은 부모 station_measurement에 FK로 묶이므로 부모를 먼저 넣는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationMeasurementBoxMapperTest {

    @Autowired
    private StationMeasurementMapper measurementMapper;

    @Autowired
    private StationMeasurementBoxMapper boxMapper;

    @Test
    void insert_andFindByParent_ordersByBoxOrder() {
        Long parentId = insertParent("SMB-01");

        boxMapper.insert(newBox(parentId, 1, 10, 20, 30, 40, 0.8));
        boxMapper.insert(newBox(parentId, 0, 1, 2, 3, 4, 0.9));

        List<StationMeasurementBox> boxes = boxMapper.findByStationMeasurementId(parentId);
        assertThat(boxes).hasSize(2);
        assertThat(boxes.get(0).getBoxOrder()).isZero();
        assertThat(boxes.get(1).getBoxOrder()).isEqualTo(1);
        assertThat(boxes.get(0).getBboxX()).isEqualTo(1);
    }

    @Test
    void insert_nullBbox_storesNull() {
        Long parentId = insertParent("SMB-02");

        StationMeasurementBox box = new StationMeasurementBox();
        box.setStationMeasurementId(parentId);
        box.setBoxOrder(0);
        box.setBboxX(null);
        box.setBboxY(null);
        box.setBboxWidth(null);
        box.setBboxHeight(null);
        box.setScore(0.5);
        box.setCreatedAt(LocalDateTime.now());
        boxMapper.insert(box);

        StationMeasurementBox saved = boxMapper.findByStationMeasurementId(parentId).get(0);
        assertThat(saved.getBboxX()).isNull();
        assertThat(saved.getScore()).isEqualTo(0.5);
        assertThat(saved.getId()).isNotNull();
    }

    private Long insertParent(String measurementId) {
        LocalDateTime now = LocalDateTime.now();
        StationMeasurement m = new StationMeasurement();
        m.setMeasurementId(measurementId);
        m.setStationId("station-1");
        m.setSchemaVersion("1.0");
        m.setMeasuredAtUtc(now);
        m.setMeasuredAtOffsetMinutes(540);
        m.setStatus(StationMeasurementStatus.OK);
        m.setReceivedAt(now);
        m.setCreatedAt(now);
        measurementMapper.insert(m);
        return m.getId();
    }

    private StationMeasurementBox newBox(Long parentId, int order, int x, int y, int w, int h, double score) {
        StationMeasurementBox box = new StationMeasurementBox();
        box.setStationMeasurementId(parentId);
        box.setBoxOrder(order);
        box.setBboxX(x);
        box.setBboxY(y);
        box.setBboxWidth(w);
        box.setBboxHeight(h);
        box.setScore(score);
        box.setCreatedAt(LocalDateTime.now());
        return box;
    }
}
