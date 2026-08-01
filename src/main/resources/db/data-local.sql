-- 로컬 개발용 선택 데이터. db/schema.sql 실행 후 사용한다.
INSERT IGNORE INTO vehicle (vehicle_id, name, active) VALUES
    ('FORKLIFT-01', 'Forklift 01', TRUE),
    ('FORKLIFT-02', 'Forklift 02', TRUE);

INSERT IGNORE INTO vehicle_current_status
    (vehicle_id, status, battery, received_at) VALUES
    ('FORKLIFT-01', 'IDLE', 100, NOW(6)),
    ('FORKLIFT-02', 'UNKNOWN', NULL, NOW(6));

INSERT IGNORE INTO storage_slot
    (slot_code, usable_height, fork_height, destination_x, destination_y,
     destination_heading, status) VALUES
    ('SLOT-01', 1.00, 0.15, 2.00, 1.00, 180.0, 'EMPTY'),
    ('SLOT-02', 1.50, 0.65, 2.00, 2.00, 180.0, 'EMPTY');
