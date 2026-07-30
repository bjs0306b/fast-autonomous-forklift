-- =====================================================================================
-- 최종 목표 스키마 (FR-202) — prompt85 8항
--
-- 기준: src/main/resources/db/schema-fr202-placement-draft.sql (= "두 번째 SQL", 최종 목표)
-- 이 파일이 확정 구조다. src/main/resources/db/schema.sql("첫 번째 SQL")은 기준이 아니다.
--
-- ★ 이 파일은 아직 어떤 yml 에서도 참조하지 않는다. 애플리케이션 코드가 아직 옛 구조
--   (vehicle.id / forklift_id / vehicle_status_history / vehicle_load_safety / rack 계층 등)를
--   쓰고 있어서, 이 스키마로 갈아타면 Mapper XML 18개 중 사실상 전부가 깨진다.
--   코드 마이그레이션이 끝난 뒤 schema.sql 자리를 이 파일로 교체할 것. (answer85.md 5장 참고)
--
-- 초안 대비 의도적으로 다르게 쓴 두 가지:
--   1) CREATE TABLE IF NOT EXISTS — 이 프로젝트의 schema.sql 이 전부 이 형태다.
--      테스트에서 spring.sql.init.mode=always 로 여러 컨텍스트에 재실행되므로 필요하다.
--   2) station_state 초기 행은 INSERT IGNORE — 재실행 시 PK 중복으로 기동이 실패하지 않게 한다.
--      (초안은 "빈 DB에서 한 번만 실행" 전제였다)
--
-- ENGINE/CHARSET 절은 넣지 않는다(기존 schema.sql 정책 — H2 MySQL 호환 모드에서 그대로 실행).
-- 길이·좌표는 m, heading 은 degree, 시각은 마이크로초 정밀도.
--
-- 트랜잭션 잠금 계약(초안 유지)
--   1. 잠금 순서: station_state -> station_session -> cargo -> transport_task/storage_slot
--   2. 세션 해제·reset 은 state 와 session 을 차례로 잠그고 소유 세션을 확인한다
--   3. 설비를 사용하지 않는 흐름은 cargo 부터 잠그고 설비 행은 요청하지 않는다
-- =====================================================================================


-- =============================================================================
-- FR-202 소유 영역: 화물과 단일 측정 설비
-- =============================================================================

CREATE TABLE IF NOT EXISTS cargo (
    cargo_id   VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '화물 고유 식별자',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '화물 등록 시각'
);

CREATE TABLE IF NOT EXISTS station_session (
    session_id VARCHAR(100) NOT NULL PRIMARY KEY COMMENT '측정 세션 고유 식별자',
    cargo_id   VARCHAR(50)  NOT NULL COMMENT '측정 대상 화물 식별자',
    CONSTRAINT fk_station_session_cargo
        FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- session_id 는 백엔드가 UUIDv4 로 생성하며 종료된 세션도 보존한다.

CREATE TABLE IF NOT EXISTS station_state (
    singleton_id      INT          NOT NULL PRIMARY KEY COMMENT '단일 스테이션 행 고정값',
    active_session_id VARCHAR(100) NULL COMMENT '현재 점유 중인 측정 세션',

    CONSTRAINT chk_station_state_singleton
        CHECK (singleton_id = 1),
    CONSTRAINT fk_station_state_active_session
        FOREIGN KEY (active_session_id) REFERENCES station_session (session_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

INSERT IGNORE INTO station_state (singleton_id, active_session_id) VALUES (1, NULL);

CREATE TABLE IF NOT EXISTS station_measurement (
    sequence_no    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '측정 결과 수신 순서',
    measurement_id VARCHAR(100) NOT NULL COMMENT '외부 측정 결과 고유 식별자',
    session_id     VARCHAR(100) NOT NULL COMMENT '측정 결과가 속한 세션',
    status         VARCHAR(30)  NOT NULL COMMENT '측정 결과 처리 상태',
    cargo_height   DOUBLE       NULL COMMENT '팔레트를 제외한 화물 높이',
    tipping_level  VARCHAR(20)  NULL COMMENT '화물 전복 위험 등급',
    overhang_ratio DOUBLE       NULL COMMENT '팔레트 기준 화물 돌출 비율',
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '측정 결과 저장 시각',

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

-- sequence_no 는 수신 순서이며 센서 측정 시각이 아니다.
-- 활성 세션의 최신 행을 선택한 뒤 상태와 안전 값을 검증한다. 교차 필드 검증은 애플리케이션이 담당한다.

-- =============================================================================
-- 차량 및 임베디드 영역
-- =============================================================================

CREATE TABLE IF NOT EXISTS vehicle (
    vehicle_id VARCHAR(50)  NOT NULL PRIMARY KEY COMMENT '차량 고유 식별자',
    name       VARCHAR(100) NOT NULL COMMENT '화면에 표시할 차량 이름',
    active     BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '차량 사용 가능 여부',
    created_at DATETIME(6)  NOT NULL COMMENT '차량 등록 시각',
    updated_at DATETIME(6)  NOT NULL COMMENT '차량 정보 수정 시각'
);

CREATE TABLE IF NOT EXISTS vehicle_current_status (
    vehicle_id       VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '상태 대상 차량 식별자',
    status           VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' COMMENT '차량 주행 상태',
    battery          INT         NULL COMMENT '차량 배터리 잔량',
    position_x       DOUBLE      NULL COMMENT '현재 지도 X 좌표',
    position_y       DOUBLE      NULL COMMENT '현재 지도 Y 좌표',
    heading          DOUBLE      NULL COMMENT '현재 차량 진행 방향',
    speed            DOUBLE      NULL COMMENT '현재 차량 속도',
    fork_height      DOUBLE      NULL COMMENT '현재 포크 높이',
    fork_state       VARCHAR(20) NULL COMMENT '현재 포크 동작 상태',
    fork_error_code  VARCHAR(50) NULL COMMENT '포크 장치 오류 코드',
    has_cargo        BOOLEAN     NULL COMMENT '화물 적재 여부',
    cargo_id         VARCHAR(50) NULL COMMENT '차량이 운반 중이라고 보고한 화물',
    footprint_length DOUBLE      NULL COMMENT '현재 차량 점유 영역 길이',
    footprint_width  DOUBLE      NULL COMMENT '현재 차량 점유 영역 너비',
    -- ★ message_at: FR-202 축소 때 뺐다가 되살린 컬럼(prompt86 A안).
    --   위치 메시지의 stale/중복 판정 기준이다. 이 컬럼이 없으면 "더 오래된 좌표가 최신 좌표를
    --   덮어쓰지 못하게" 하는 방어를 DB 에서 할 수 없고, 인메모리 판정만 남아 재시작·다중 인스턴스
    --   상황에서 뚫린다. 송신 측이 찍은 시각이며 서버 수신 시각(received_at)과 구분된다.
    message_at       DATETIME(6) NULL COMMENT '송신 측이 명시한 메시지 생성 시각(stale 판정 기준)',
    received_at      DATETIME(6) NOT NULL COMMENT '최신 상태 메시지 수신 시각',
    CONSTRAINT fk_vehicle_current_status_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);

-- cargo_id 는 차량이 보고한 관측값으로 저장한다(FK 로 강제하지 않는다).

CREATE TABLE IF NOT EXISTS embedded_vehicle_command (
    command_id             VARCHAR(100)  NOT NULL PRIMARY KEY COMMENT '명령 고유 식별자',
    vehicle_id             VARCHAR(50)   NOT NULL COMMENT '명령 대상 차량 식별자',
    command                VARCHAR(30)   NOT NULL COMMENT '실행할 명령 종류',
    target_system          VARCHAR(20)   NOT NULL COMMENT '명령을 처리할 대상 시스템',
    status                 VARCHAR(20)   NOT NULL COMMENT '명령 처리 상태',
    issued_at              DATETIME(6)   NOT NULL COMMENT '명령 생성 시각',
    completed_at           DATETIME(6)   NULL COMMENT '명령 처리 완료 시각',
    error_code             VARCHAR(50)   NULL COMMENT '명령 실패 오류 코드',
    result_message         VARCHAR(500)  NULL COMMENT '명령 처리 결과 설명',

    CONSTRAINT chk_embedded_vehicle_command_target_system
        CHECK (target_system IN ('ROS2', 'EMBEDDED', 'ALL')),
    CONSTRAINT chk_embedded_vehicle_command_status
        CHECK (status IN (
            'PENDING', 'PUBLISHED', 'PUBLISH_FAILED', 'ACCEPTED', 'IN_PROGRESS',
            'SUCCESS', 'FAILED', 'REJECTED', 'CANCELLED'
        )),
    CONSTRAINT fk_embedded_vehicle_command_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_embedded_vehicle_command_vehicle_issued (vehicle_id, issued_at DESC)
);

CREATE TABLE IF NOT EXISTS embedded_error_history (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '오류 이력 내부 식별자',
    vehicle_id   VARCHAR(50)  NOT NULL COMMENT '오류가 발생한 차량 식별자',
    error_code   VARCHAR(50)  NOT NULL COMMENT '발생한 오류 코드',
    error_source VARCHAR(20)  NOT NULL COMMENT '오류 발생 장치 또는 시스템',
    severity     VARCHAR(20)  NOT NULL COMMENT '오류 심각도',
    message      VARCHAR(500) NULL COMMENT '오류 상세 설명',
    occurred_at  DATETIME(6)  NOT NULL COMMENT '오류 발생 시각',

    CONSTRAINT chk_embedded_error_history_source
        CHECK (error_source IN ('DRIVE', 'STEERING', 'FORK', 'LIMIT_SWITCH', 'UART', 'SYSTEM')),
    CONSTRAINT chk_embedded_error_history_severity
        CHECK (severity IN ('WARNING', 'ERROR', 'CRITICAL')),
    CONSTRAINT fk_embedded_error_history_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (vehicle_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    INDEX idx_embedded_error_history_vehicle_occurred (vehicle_id, occurred_at DESC)
);

-- =============================================================================
-- FR-202 소유 영역: 적재 위치, 운반 작업과 명령
-- =============================================================================

-- slot_code 는 사전 정의된 적재 위치를 식별한다.
-- usable_height 는 수직 가용 높이, fork_height 는 목표 포크 높이다.
CREATE TABLE IF NOT EXISTS storage_slot (
    slot_code           VARCHAR(50) NOT NULL PRIMARY KEY COMMENT '적재 위치 고유 식별자',
    usable_height       DOUBLE      NOT NULL COMMENT '화물을 넣을 수 있는 수직 가용 높이',
    fork_height         DOUBLE      NOT NULL COMMENT '적재 시 목표 포크 높이',
    destination_x       DOUBLE      NOT NULL COMMENT '적재 접근 지점 X 좌표',
    destination_y       DOUBLE      NOT NULL COMMENT '적재 접근 지점 Y 좌표',
    destination_heading DOUBLE      NOT NULL COMMENT '적재 접근 시 차량 방향',
    status              VARCHAR(20) NOT NULL DEFAULT 'EMPTY' COMMENT '적재 위치 점유 상태',
    reserved_task_id    BIGINT      NULL COMMENT '현재 위치를 예약한 운반 작업',
    stored_cargo_id     VARCHAR(50) NULL COMMENT '현재 위치에 적재된 화물',

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

CREATE TABLE IF NOT EXISTS transport_task (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '운반 작업 내부 식별자',
    task_code             VARCHAR(50) NOT NULL COMMENT '외부에 노출하는 작업 코드',
    cargo_id              VARCHAR(50) NOT NULL COMMENT '운반 대상 화물 식별자',
    measurement_id        VARCHAR(100) NOT NULL COMMENT '배치 판단에 사용한 측정 결과',
    vehicle_id            VARCHAR(50) NULL COMMENT '작업에 배정된 차량 식별자',
    source_x              DOUBLE      NOT NULL COMMENT '픽업 지점 X 좌표 스냅샷',
    source_y              DOUBLE      NOT NULL COMMENT '픽업 지점 Y 좌표 스냅샷',
    source_heading        DOUBLE      NOT NULL COMMENT '픽업 시 차량 방향 스냅샷',
    destination_slot_code VARCHAR(50) NOT NULL COMMENT '선택된 목적지 적재 위치',
    destination_x         DOUBLE      NOT NULL COMMENT '목적지 X 좌표 스냅샷',
    destination_y         DOUBLE      NOT NULL COMMENT '목적지 Y 좌표 스냅샷',
    destination_heading   DOUBLE      NOT NULL COMMENT '목적지 차량 방향 스냅샷',
    fork_height           DOUBLE      NOT NULL COMMENT '적재 목표 포크 높이 스냅샷',
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '운반 작업 진행 상태',
    assigned_at           DATETIME(6) NULL COMMENT '차량 배정 시각',
    started_at            DATETIME(6) NULL COMMENT '작업 시작 시각',
    picked_up_at          DATETIME(6) NULL COMMENT '화물 픽업 완료 시각',
    completed_at          DATETIME(6) NULL COMMENT '운반 작업 완료 시각',
    failed_at             DATETIME(6) NULL COMMENT '운반 작업 실패 시각',
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '운반 작업 생성 시각',

    CONSTRAINT uk_transport_task_task_code UNIQUE (task_code),
    CONSTRAINT chk_transport_task_status
        CHECK (status IN (
            'PENDING', 'ASSIGNED', 'MOVING_TO_PICKUP', 'PICKING_UP',
            'TRANSPORTING', 'PLACING', 'COMPLETED', 'FAILED', 'CANCELLED'
        )),
    CONSTRAINT fk_transport_task_cargo
        FOREIGN KEY (cargo_id) REFERENCES cargo (cargo_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_transport_task_measurement
        FOREIGN KEY (measurement_id) REFERENCES station_measurement (measurement_id)
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
    INDEX idx_transport_task_measurement (measurement_id),
    INDEX idx_transport_task_slot (destination_slot_code),
    INDEX idx_transport_task_created (created_at DESC, id DESC)
);

-- storage_slot.reserved_task_id -> transport_task.id 는 순환 참조라 테이블 생성 후 ALTER 로 붙인다.
-- (MySQL 은 ADD CONSTRAINT IF NOT EXISTS 를 지원하지 않으므로, 재실행 시 "Duplicate foreign key
--  constraint name" 오류가 날 수 있다 — 이미 적용됐다는 뜻이므로 무시한다.)
ALTER TABLE storage_slot
    ADD CONSTRAINT fk_storage_slot_reserved_task
    FOREIGN KEY (reserved_task_id) REFERENCES transport_task (id)
    ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE TABLE IF NOT EXISTS transport_command (
    command_id      VARCHAR(50)   NOT NULL PRIMARY KEY COMMENT '운반 명령 고유 식별자',
    task_id         BIGINT        NOT NULL COMMENT '명령이 속한 운반 작업',
    vehicle_id      VARCHAR(50)   NOT NULL COMMENT '명령 대상 차량 식별자',
    status          VARCHAR(20)   NOT NULL DEFAULT 'CREATED' COMMENT '명령 전송 및 처리 상태',
    failure_reason  VARCHAR(500)  NULL COMMENT '명령 실패 사유',
    completed_at    DATETIME(6)   NULL COMMENT '명령 처리 완료 시각',
    created_at      DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '운반 명령 생성 시각',

    CONSTRAINT chk_transport_command_status
        CHECK (status IN (
            'CREATED', 'PUBLISHED', 'SUCCEEDED',
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
    INDEX idx_transport_command_task_order (task_id, created_at DESC, command_id DESC),
    INDEX idx_transport_command_created (created_at DESC, command_id DESC)
);

-- =====================================================================================
-- 최종 스키마에서 제거된 테이블 (첫 번째 SQL 에만 있던 것 — 여기 없는 것이 의도다)
--   vehicle_status_history, vehicle_fork_current_status, vehicle_load_safety,
--   ai_cargo_analysis, ai_cargo_detection_box, station_measurement_box,
--   pallet, rack, rack_level
-- 각 테이블의 코드 사용처와 제거 영향은 answer85.md 4·5장 참고.
-- =====================================================================================
