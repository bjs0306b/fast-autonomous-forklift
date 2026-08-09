-- F.A.S.T. MVP 백엔드 스키마
-- 기준 문서: docs/backend-api/optimal-placement.md
-- 길이와 지도 좌표는 m, 방향은 [0, 360) 범위의 degree를 사용한다.
-- 신규 데이터베이스 생성용 파일이며 운영 데이터 마이그레이션은 범위에서 제외한다.
--
-- ⚠️ 모든 테이블에 utf8mb4 를 명시한다. 빠뜨리면 DB 기본 문자셋(대개 latin1)으로 만들어져
--    한글이 '?' 로 저장된다 — JDBC URL 에 characterEncoding=UTF-8 이 있어도 소용없다.
--    컬럼이 담지 못하는 문자는 MySQL 이 '?' 로 바꿔 넣고, 그 뒤에는 복원할 수 없다.
--    (실제로 겪었다: 차량 이름이 '????? ??? 1?' 로 나왔다. db/fix-charset-utf8mb4.sql 참고)

CREATE TABLE IF NOT EXISTS cargo (
    cargo_id   BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '백엔드가 자동 생성하는 화물 고유 식별자',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '화물 등록 시각'
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS station_session (
    session_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '측정 세션 식별자',
    cargo_id   BIGINT       NOT NULL COMMENT '측정 대상 화물 식별자',
    CONSTRAINT fk_station_session_cargo
        FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS station_state (
    singleton_id      INT          NOT NULL PRIMARY KEY COMMENT '단일 행을 보장하는 고정값 1',
    active_session_id VARCHAR(100) NULL COMMENT '현재 활성 측정 세션',
    acquired_at       DATETIME(6)  NULL COMMENT '측정 설비 점유 시작 시각',
    CONSTRAINT chk_station_state_singleton CHECK (singleton_id = 1),
    CONSTRAINT chk_station_state_occupancy CHECK (
        (active_session_id IS NULL AND acquired_at IS NULL)
        OR (active_session_id IS NOT NULL AND acquired_at IS NOT NULL)
    ),
    CONSTRAINT fk_station_state_session
        FOREIGN KEY (active_session_id) REFERENCES station_session (session_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO station_state (singleton_id, active_session_id, acquired_at)
SELECT 1, NULL, NULL
WHERE NOT EXISTS (SELECT 1 FROM station_state WHERE singleton_id = 1);

CREATE TABLE IF NOT EXISTS station_measurement (
    sequence_no    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '백엔드 측정 결과 수신 순서',
    measurement_id VARCHAR(100) NOT NULL COMMENT '측정 결과 중복 방지 식별자',
    session_id     VARCHAR(100) NOT NULL COMMENT '측정 세션 식별자',
    status         VARCHAR(30)  NOT NULL COMMENT '측정 처리 상태',
    cargo_width         DOUBLE       NULL COMMENT '화물 폭(m). 스테이션 미전송 시 NULL — 폭 검사 생략',
    -- 아래 셋은 관제 화면 오버레이 전용이다. 지게차 이동에는 쓰지 않는다
    -- (화면 픽셀 좌표라 창고 맵 좌표로 바꾸려면 카메라 외부 파라미터가 필요하다).
    frame_width         INT          NULL COMMENT 'boxes 픽셀 좌표의 기준 가로 해상도',
    frame_height        INT          NULL COMMENT 'boxes 픽셀 좌표의 기준 세로 해상도',
    boxes_json          JSON         NULL COMMENT '검출 상자별 [x1,y1,x2,y2]·score. 화면 오버레이용',
    cargo_height   DOUBLE       NULL COMMENT '팔레트를 제외한 화물 높이(m)',
    tipping_level  VARCHAR(20)  NULL COMMENT '전복 위험 등급(SAFE, WARNING, DANGER)',
    overhang_ratio DOUBLE       NULL COMMENT '팔레트 대비 화물 돌출 비율',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '백엔드 저장 시각',
    CONSTRAINT uk_station_measurement_id UNIQUE (measurement_id),
    CONSTRAINT uk_station_measurement_session UNIQUE (session_id),
    CONSTRAINT chk_station_measurement_status
        CHECK (status IN ('OK', 'DIMENSIONS_ONLY', 'NO_DETECTION', 'UNRELIABLE')),
    CONSTRAINT chk_station_measurement_height
        CHECK (cargo_height IS NULL OR cargo_height > 0),
    CONSTRAINT chk_station_measurement_tipping
        CHECK (tipping_level IS NULL OR tipping_level IN ('SAFE', 'WARNING', 'DANGER')),
    CONSTRAINT chk_station_measurement_overhang
        CHECK (overhang_ratio IS NULL OR overhang_ratio >= 0),
    CONSTRAINT fk_station_measurement_session
        FOREIGN KEY (session_id) REFERENCES station_session (session_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vehicle (
    vehicle_id VARCHAR(50)  NOT NULL PRIMARY KEY COMMENT '차량 고유 식별자',
    name       VARCHAR(100) NOT NULL COMMENT '차량 표시 이름',
    active     BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '차량 사용 가능 여부',
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '차량 등록 시각',
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '차량 정보 수정 시각'
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vehicle_current_status (
    vehicle_id      VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '상태 대상 차량 식별자',
    status          VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' COMMENT '차량 주행 상태',
    position_x      DOUBLE      NULL COMMENT '지도 X 좌표(m)',
    position_y      DOUBLE      NULL COMMENT '지도 Y 좌표(m)',
    position_frame  VARCHAR(10) NULL COMMENT '좌표계(map 또는 odom)',
    heading         DOUBLE      NULL COMMENT '차량 진행 방향(degree)',
    speed           DOUBLE      NULL COMMENT '차량 속도(m/s)',
    has_cargo       BOOLEAN     NULL COMMENT '화물 적재 여부',
    cargo_id        BIGINT      NULL COMMENT '차량이 보고한 화물 식별자',
    message_at      DATETIME(6) NULL COMMENT '최신 위치 메시지 원본 발생 시각',
    received_at     DATETIME(6) NOT NULL COMMENT '최신 상태 수신 시각',
    CONSTRAINT chk_vehicle_status_frame CHECK (position_frame IS NULL OR position_frame IN ('map', 'odom')),
    CONSTRAINT chk_vehicle_status_heading CHECK (heading IS NULL OR (heading >= 0 AND heading < 360)),
    CONSTRAINT fk_vehicle_current_status_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS storage_slot (
    slot_code           VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '적재 위치 식별자',
    usable_height       DOUBLE      NOT NULL COMMENT '수직 가용 높이(m)',
    -- NULL 은 "폭 제약을 모른다"는 뜻이고 폭 검사를 건너뛴다(PlacementService 주석 참고).
    -- 0 으로 두면 모든 화물이 탈락하므로 쓰지 말 것.
    usable_width        DOUBLE      NULL COMMENT '수평 가용 폭(m). NULL 이면 폭 제약 없음',
    fork_height         DOUBLE      NOT NULL COMMENT '목표 포크 높이(m)',
    destination_x       DOUBLE      NOT NULL COMMENT '적재 위치 접근 X 좌표(m)',
    destination_y       DOUBLE      NOT NULL COMMENT '적재 위치 접근 Y 좌표(m)',
    destination_heading DOUBLE      NOT NULL COMMENT '적재 위치 접근 방향(degree)',
    status              VARCHAR(20) NOT NULL DEFAULT 'EMPTY' COMMENT '적재 위치 상태(EMPTY, RESERVED, OCCUPIED, BLOCKED)',
    reserved_task_id    BIGINT      NULL COMMENT '현재 적재 위치를 예약한 운반 작업',
    stored_cargo_id     BIGINT      NULL COMMENT '현재 적재된 화물 식별자',
    CONSTRAINT uk_storage_slot_cargo UNIQUE (stored_cargo_id),
    CONSTRAINT chk_storage_slot_geometry CHECK (usable_height > 0 AND fork_height >= 0),
    CONSTRAINT chk_storage_slot_status CHECK (status IN ('EMPTY', 'RESERVED', 'OCCUPIED', 'BLOCKED')),
    CONSTRAINT chk_storage_slot_state CHECK (
        (status IN ('EMPTY', 'BLOCKED') AND reserved_task_id IS NULL AND stored_cargo_id IS NULL)
        OR (status = 'RESERVED' AND reserved_task_id IS NOT NULL AND stored_cargo_id IS NULL)
        OR (status = 'OCCUPIED' AND reserved_task_id IS NULL AND stored_cargo_id IS NOT NULL)
    ),
    CONSTRAINT fk_storage_slot_cargo
        FOREIGN KEY (stored_cargo_id) REFERENCES cargo (cargo_id),
    INDEX idx_storage_slot_status (status),
    INDEX idx_storage_slot_reserved_task (reserved_task_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS transport_task (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '운반 작업 내부 식별자',
    task_code             VARCHAR(50)  NOT NULL COMMENT '외부 운반 작업 식별자',
    cargo_id              BIGINT       NOT NULL COMMENT '운반 대상 화물 식별자',
    measurement_session_id VARCHAR(100) NULL COMMENT 'AI의 세션 생성 요청 후 연결된 세션 식별자',
    measurement_id        VARCHAR(100) NULL COMMENT '측정 완료 후 연결되는 배치 판단 결과',
    vehicle_id            VARCHAR(50)  NULL COMMENT '배정된 차량 식별자',
    destination_slot_code VARCHAR(50)  NULL COMMENT '측정 완료 후 선택되는 목적지 적재 위치',
    destination_x         DOUBLE       NULL COMMENT '목적지 X 좌표 스냅샷(m)',
    destination_y         DOUBLE       NULL COMMENT '목적지 Y 좌표 스냅샷(m)',
    destination_heading   DOUBLE       NULL COMMENT '목적지 방향 스냅샷(degree)',
    fork_height           DOUBLE       NULL COMMENT '목표 포크 높이 스냅샷(m)',
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '운반 작업 상태',
    assigned_at           DATETIME(6)  NULL COMMENT '차량 배정 시각',
    started_at            DATETIME(6)  NULL COMMENT '운반 작업 시작 시각',
    measurement_requested_at DATETIME(6) NULL COMMENT 'AI 측정 요청 발행 시각. TTL 실패 판정 기준',
    completed_at          DATETIME(6)  NULL COMMENT '운반 작업 완료 시각',
    failed_at             DATETIME(6)  NULL COMMENT '운반 작업 실패 시각',
    failure_code          VARCHAR(40)  NULL COMMENT '실패 원인 코드. 관제 화면이 원인별 문구를 고르는 근거',
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '운반 작업 생성 시각',
    CONSTRAINT uk_transport_task_code UNIQUE (task_code),
    CONSTRAINT uk_transport_task_measurement_session UNIQUE (measurement_session_id),
    CONSTRAINT uk_transport_task_measurement UNIQUE (measurement_id),
    CONSTRAINT chk_transport_task_status CHECK (status IN (
        'PENDING', 'ASSIGNED', 'MOVING_TO_PICKUP', 'MEASURING', 'PICKING_UP',
        'TRANSPORTING', 'PLACING', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT chk_transport_task_failure_code CHECK (
        failure_code IS NULL OR failure_code IN (
            'MEASUREMENT_NO_DETECTION', 'MEASUREMENT_DISTANCE_UNRELIABLE',
            'MEASUREMENT_PALLET_NOT_DETECTED', 'PLACEMENT_INELIGIBLE',
            'MEASUREMENT_NO_RESPONSE', 'PLACEMENT_SLOT_UNAVAILABLE'
        )
    ),
    CONSTRAINT chk_transport_task_placement CHECK (
        (measurement_id IS NULL
            AND destination_slot_code IS NULL
            AND destination_x IS NULL
            AND destination_y IS NULL
            AND destination_heading IS NULL
            AND fork_height IS NULL)
        OR
        (measurement_id IS NOT NULL
            AND destination_slot_code IS NOT NULL
            AND destination_x IS NOT NULL
            AND destination_y IS NOT NULL
            AND destination_heading IS NOT NULL
            AND fork_height IS NOT NULL)
    ),
    CONSTRAINT fk_transport_task_cargo
        FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id),
    CONSTRAINT fk_transport_task_measurement_session
        FOREIGN KEY (measurement_session_id) REFERENCES station_session (session_id),
    CONSTRAINT fk_transport_task_measurement
        FOREIGN KEY (measurement_id) REFERENCES station_measurement (measurement_id),
    CONSTRAINT fk_transport_task_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id),
    CONSTRAINT fk_transport_task_slot
        FOREIGN KEY (destination_slot_code) REFERENCES storage_slot (slot_code),
    INDEX idx_transport_task_status (status),
    INDEX idx_transport_task_measurement_timeout (status, measurement_requested_at),
    INDEX idx_transport_task_vehicle (vehicle_id),
    INDEX idx_transport_task_cargo_status (cargo_id, status),
    INDEX idx_transport_task_slot (destination_slot_code)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- storage_slot과 transport_task의 상호 생성 순환을 피하려고 reserved_task_id에는 FK를 두지 않는다.
-- 예약 상태 변경은 애플리케이션의 조건부 UPDATE로 동시성을 보호한다.

CREATE TABLE IF NOT EXISTS vehicle_command (
    command_id     VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '차량 명령 고유 식별자',
    task_id        BIGINT       NULL COMMENT '연결된 운반 작업 식별자',
    vehicle_id     VARCHAR(50)  NOT NULL COMMENT '명령 대상 차량 식별자',
    command        VARCHAR(30)  NOT NULL COMMENT '실행할 명령 이름',
    target_system  VARCHAR(20)  NOT NULL COMMENT '명령 대상 시스템(ROS2, EMBEDDED, ALL)',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '명령 처리 상태',
    result_message VARCHAR(500) NULL COMMENT '명령 처리 결과 또는 실패 상세',
    completed_at   DATETIME(6)  NULL COMMENT '명령 완료 시각',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '명령 생성 시각',
    CONSTRAINT chk_vehicle_command_target CHECK (target_system IN ('ROS2', 'EMBEDDED', 'ALL')),
    CONSTRAINT chk_vehicle_command_status CHECK (status IN (
        'PENDING', 'PUBLISHED', 'PUBLISH_FAILED', 'ACCEPTED', 'IN_PROGRESS',
        'SUCCESS', 'FAILED', 'REJECTED', 'CANCELLED'
    )),
    CONSTRAINT fk_vehicle_command_task
        FOREIGN KEY (task_id) REFERENCES transport_task (id),
    CONSTRAINT fk_vehicle_command_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id),
    INDEX idx_vehicle_command_task (task_id),
    INDEX idx_vehicle_command_vehicle (vehicle_id),
    INDEX idx_vehicle_command_status (status)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 교통 관제 정지/재개 이벤트 (FR-502-1a)
--
-- 발행된 명령 자체는 vehicle_command 에 남지만, 거기엔 "왜 그렇게 판단했는지"를 담을 자리가
-- 없다(result_message 는 명령 처리 결과라 축이 다르다). 사고 후 "그때 왜 멈췄나"를 되짚으려면
-- 안전거리·구역·상대 차량 같은 근거가 남아 있어야 한다.
--
-- command_id 에 FK 를 걸지 않는다: 명령 발행이 실패해도(PUBLISH_FAILED 이전 단계에서 예외)
-- 판단 자체는 기록으로 남겨야 하는데, FK 가 있으면 그 경우 이벤트를 넣을 수 없다.
CREATE TABLE IF NOT EXISTS traffic_control_event (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    vehicle_id             VARCHAR(50)  NOT NULL COMMENT '정지/재개 대상 차량',
    event_type             VARCHAR(20)  NOT NULL COMMENT 'HOLD(정지) 또는 RELEASE(재개)',
    reason_code            VARCHAR(40)  NULL COMMENT '정지 사유 코드. RELEASE 면 해제된 직전 사유',
    reason_detail          VARCHAR(500) NULL COMMENT '사람이 읽는 사유 상세',
    counterpart_vehicle_id VARCHAR(50)  NULL COMMENT '안전거리 위반 상대 또는 구역 점유 차량',
    slot_code              VARCHAR(50)  NULL COMMENT '작업 구역 사유일 때의 선반 코드',
    distance_m             DOUBLE       NULL COMMENT '판단 당시 유효 거리(m)',
    command_id             VARCHAR(100) NULL COMMENT '이 판단으로 발행된 명령. 발행 실패면 NULL',
    occurred_at            DATETIME(6)  NOT NULL COMMENT '판단 시각',
    CONSTRAINT chk_traffic_event_type CHECK (event_type IN ('HOLD', 'RELEASE')),
    CONSTRAINT chk_traffic_event_reason CHECK (
        reason_code IS NULL OR reason_code IN ('SAFETY_DISTANCE', 'WORK_ZONE_OCCUPIED')
    ),
    CONSTRAINT fk_traffic_event_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id),
    INDEX idx_traffic_event_vehicle (vehicle_id, occurred_at),
    INDEX idx_traffic_event_occurred (occurred_at)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
