-- FR-202 최적 적재 위치 산출용 전체 스키마 설계 초안
--
-- 빈 데이터베이스에 적용하는 clean-install 기준 설계 문서다.
-- 현재 애플리케이션 시작 시 자동 실행되는 스키마가 아니며, 기존 schema.sql을 대체하지 않는다.
-- 길이와 좌표의 단위는 m, heading의 단위는 degree다. 모든 시각은 마이크로초 정밀도로 저장한다.
-- 고정 설비 규격과 시뮬레이션 배율은 애플리케이션 설정에서 관리하며 이 스키마에 저장하지 않는다.

-- 트랜잭션 잠금 계약
-- 1. 설비 관련 흐름은 station_state -> station_session -> cargo -> transport_task/storage_slot 순서로 잠근다.
-- 2. 세션 해제와 운영자 reset은 한 트랜잭션에서 station_state를 먼저 잠근 뒤 station_session을 잠근다.
-- 3. 세션 해제 시 소유 세션이 일치할 때만 active_session_id를 비운다.
-- 4. 설비를 사용하지 않는 작업 흐름은 cargo부터 잠그며 이후 설비 행을 요청하지 않는다.

-- =============================================================================
-- FR-202 소유 영역: 화물과 단일 측정 설비
-- =============================================================================

CREATE TABLE cargo (
    cargo_id  VARCHAR(50) NOT NULL PRIMARY KEY,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE station_session (
    session_id VARCHAR(100) NOT NULL PRIMARY KEY,
    cargo_id   VARCHAR(50)  NOT NULL,
    CONSTRAINT fk_station_session_cargo
        FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- session_id는 acquire 요청자가 제공하지 않는다. 백엔드가 매번 새 UUIDv4를 생성하며,
-- 종료된 행도 계속 보존하여 과거 토큰의 재사용을 데이터베이스 기본 키로 차단한다.

CREATE TABLE station_state (
    singleton_id      INT          NOT NULL PRIMARY KEY,
    active_session_id VARCHAR(100) NULL,

    CONSTRAINT chk_station_state_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT fk_station_state_active_session
        FOREIGN KEY (active_session_id) REFERENCES station_session (session_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- 빈 데이터베이스에서는 정확히 한 번 실행되는 엄격한 초기값이다.
INSERT INTO station_state (singleton_id, active_session_id)
VALUES (1, NULL);

CREATE TABLE station_measurement (
    sequence_no   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    measurement_id VARCHAR(100) NOT NULL,
    session_id    VARCHAR(100) NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    cargo_height  DOUBLE       NULL,
    tipping_level VARCHAR(20)  NULL,
    overhang_ratio DOUBLE      NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_station_measurement_measurement_id UNIQUE (measurement_id),
    CONSTRAINT chk_station_measurement_status
        CHECK (status IN ('OK', 'DIMENSIONS_ONLY', 'NO_DETECTION', 'UNRELIABLE')),
    CONSTRAINT chk_station_measurement_height
        CHECK (cargo_height IS NULL OR cargo_height > 0),
    CONSTRAINT chk_station_measurement_tipping_level
        CHECK (tipping_level IS NULL OR tipping_level IN ('SAFE', 'WARNING', 'DANGER')),
    CONSTRAINT chk_station_measurement_overhang
        CHECK (overhang_ratio IS NULL OR overhang_ratio >= 0),
    CONSTRAINT fk_station_measurement_session
        FOREIGN KEY (session_id) REFERENCES station_session (session_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_station_measurement_session_latest (session_id, sequence_no DESC)
);

-- sequence_no는 설비 잠금 안에서 배정되는 수신 순서다. 센서 측정 시각이나 커밋 시각을 뜻하지 않는다.
-- 배치는 활성 세션에서 sequence_no가 가장 큰 행을 먼저 선택한 뒤 상태와 안전 값을 판정한다.
-- 결과 조합의 교차 필드 규칙은 애플리케이션 검증기가 담당하고, 이 테이블은 스칼라 범위만 제한한다.

-- =============================================================================
-- 동결된 차량 및 임베디드 영역
-- =============================================================================

CREATE TABLE vehicle (
    vehicle_id   VARCHAR(50)  NOT NULL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    source       VARCHAR(20)  NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL
);

CREATE TABLE vehicle_current_status (
    vehicle_id       VARCHAR(50) PRIMARY KEY,
    status           VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    battery          INT         NULL,
    position_x       DOUBLE      NULL,
    position_y       DOUBLE      NULL,
    heading          DOUBLE      NULL,
    speed            DOUBLE      NULL,
    fork_height      DOUBLE      NULL,
    fork_state       VARCHAR(20) NULL,
    limit_bottom     BOOLEAN     NULL,
    fork_error_code  VARCHAR(50) NULL,
    has_cargo        BOOLEAN     NULL,
    cargo_id         VARCHAR(50) NULL,
    footprint_length DOUBLE      NULL,
    footprint_width  DOUBLE      NULL,
    received_at      DATETIME(6) NOT NULL,
    CONSTRAINT fk_vehicle_current_status_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- 차량 상태 메시지는 화물 등록보다 먼저 도착할 수 있으므로 cargo_id는 관측값으로만 보존한다.

CREATE TABLE embedded_vehicle_command (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id              VARCHAR(100)  NOT NULL,
    forklift_id             VARCHAR(50)   NOT NULL,
    command                 VARCHAR(30)   NOT NULL,
    target_system           VARCHAR(20)   NOT NULL,
    command_category        VARCHAR(20)   NOT NULL,
    payload_json            VARCHAR(1000) NULL,
    reason                  VARCHAR(100)  NULL,
    status                  VARCHAR(20)   NOT NULL,
    issued_at               DATETIME(6)   NOT NULL,
    published_at            DATETIME(6)   NULL,
    completed_at            DATETIME(6)   NULL,
    error_code              VARCHAR(50)   NULL,
    result_message          VARCHAR(500)  NULL,
    stopped_actions         VARCHAR(100)  NULL,
    emergency_stop_applied  BOOLEAN       NULL,
    requires_reset          BOOLEAN       NULL,
    CONSTRAINT uk_embedded_vehicle_command_command_id UNIQUE (command_id),
    INDEX idx_embedded_vehicle_command_forklift_issued (forklift_id, issued_at DESC)
);

CREATE TABLE embedded_error_history (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    forklift_id  VARCHAR(50)  NOT NULL,
    error_code   VARCHAR(50)  NOT NULL,
    error_source VARCHAR(20)  NOT NULL,
    severity     VARCHAR(20)  NOT NULL,
    message      VARCHAR(500) NULL,
    occurred_at  DATETIME(6)  NOT NULL,
    INDEX idx_embedded_error_history_forklift_occurred (forklift_id, occurred_at DESC)
);

-- =============================================================================
-- FR-202 소유 영역: 적재 위치, 운반 작업과 명령
-- =============================================================================

-- slot_code는 시뮬레이션에 미리 정의된 적재 위치를 식별한다.
-- usable_height는 적재 가능한 수직 여유, fork_height는 적재 시 포크 목표 높이다.
CREATE TABLE storage_slot (
    slot_code           VARCHAR(50) NOT NULL PRIMARY KEY,
    usable_height       DOUBLE      NOT NULL,
    fork_height         DOUBLE      NOT NULL,
    destination_x       DOUBLE      NOT NULL,
    destination_y       DOUBLE      NOT NULL,
    destination_heading DOUBLE      NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'EMPTY',
    reserved_task_id    BIGINT      NULL,
    stored_cargo_id     VARCHAR(50) NULL,

    CONSTRAINT uk_storage_slot_stored_cargo UNIQUE (stored_cargo_id),
    CONSTRAINT chk_storage_slot_geometry
        CHECK (usable_height > 0 AND fork_height >= 0),
    CONSTRAINT chk_storage_slot_status
        CHECK (status IN ('EMPTY', 'RESERVED', 'OCCUPIED', 'BLOCKED')),
    CONSTRAINT chk_storage_slot_state_shape
        CHECK (
            (status IN ('EMPTY', 'BLOCKED')
                AND reserved_task_id IS NULL AND stored_cargo_id IS NULL)
            OR
            (status = 'RESERVED'
                AND reserved_task_id IS NOT NULL AND stored_cargo_id IS NULL)
            OR
            (status = 'OCCUPIED'
                AND reserved_task_id IS NULL AND stored_cargo_id IS NOT NULL)
        ),
    CONSTRAINT fk_storage_slot_stored_cargo
        FOREIGN KEY (stored_cargo_id) REFERENCES cargo (cargo_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_storage_slot_status (status),
    INDEX idx_storage_slot_reserved_task (reserved_task_id)
);

CREATE TABLE transport_task (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_code           VARCHAR(50) NOT NULL,
    cargo_id            VARCHAR(50) NOT NULL,
    vehicle_id          VARCHAR(50) NULL,
    source_x            DOUBLE      NOT NULL,
    source_y            DOUBLE      NOT NULL,
    source_heading      DOUBLE      NOT NULL,
    destination_slot_code VARCHAR(50) NOT NULL,
    destination_x       DOUBLE      NOT NULL,
    destination_y       DOUBLE      NOT NULL,
    destination_heading DOUBLE      NOT NULL,
    fork_height         DOUBLE      NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    assigned_at         DATETIME(6) NULL,
    started_at          DATETIME(6) NULL,
    picked_up_at        DATETIME(6) NULL,
    completed_at        DATETIME(6) NULL,
    failed_at           DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_transport_task_task_code UNIQUE (task_code),
    CONSTRAINT chk_transport_task_status
        CHECK (status IN (
            'PENDING', 'ASSIGNED', 'MOVING_TO_PICKUP', 'PICKING_UP',
            'TRANSPORTING', 'PLACING', 'COMPLETED', 'FAILED', 'CANCELLED'
        )),
    CONSTRAINT fk_transport_task_cargo
        FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_transport_task_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_transport_task_slot
        FOREIGN KEY (destination_slot_code) REFERENCES storage_slot (slot_code)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_transport_task_status (status),
    INDEX idx_transport_task_vehicle (vehicle_id),
    INDEX idx_transport_task_cargo_status (cargo_id, status),
    INDEX idx_transport_task_slot (destination_slot_code),
    INDEX idx_transport_task_created (created_at DESC, id DESC)
);

-- 슬롯과 작업의 순환 참조는 작업 테이블 생성 후 예약 FK를 추가하여 해소한다.
ALTER TABLE storage_slot
    ADD CONSTRAINT fk_storage_slot_reserved_task
    FOREIGN KEY (reserved_task_id) REFERENCES transport_task (id)
    ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE TABLE transport_command (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    command_id      VARCHAR(50)   NOT NULL,
    task_id         BIGINT        NOT NULL,
    vehicle_id      VARCHAR(50)   NOT NULL,
    command_type    VARCHAR(30)   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'CREATED',
    payload         VARCHAR(2000) NULL,
    failure_reason  VARCHAR(500)  NULL,
    published_at    DATETIME(6)   NULL,
    acknowledged_at DATETIME(6)   NULL,
    completed_at    DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_transport_command_command_id UNIQUE (command_id),
    CONSTRAINT chk_transport_command_status
        CHECK (status IN (
            'CREATED', 'PUBLISHED', 'ACKNOWLEDGED', 'SUCCEEDED',
            'FAILED', 'PUBLISH_FAILED', 'TIMEOUT'
        )),
    CONSTRAINT fk_transport_command_task
        FOREIGN KEY (task_id) REFERENCES transport_task (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_transport_command_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_transport_command_task (task_id),
    INDEX idx_transport_command_vehicle (vehicle_id),
    INDEX idx_transport_command_status (status),
    INDEX idx_transport_command_task_order (task_id, id DESC),
    INDEX idx_transport_command_created (created_at DESC, id DESC)
);
