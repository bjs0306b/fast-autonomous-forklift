-- 로컬 개발 전용 더미 데이터(prompt16.md 8장). application-local.yml에서만
-- spring.sql.init.data-locations로 이 파일을 가리키므로, 운영/테스트 환경에는 로드되지 않는다.
--
-- INSERT IGNORE를 쓴 이유: 로컬 개발 중에는 애플리케이션을 자주 재시작하는데, 이 파일이 매번 다시
-- 실행돼도(spring.sql.init.mode=always) vehicle_id UNIQUE 제약 위반으로 기동이 실패하지 않도록 하기
-- 위함이다(멱등성 확보). 이미 값이 있으면 조용히 건너뛰고, 처음 실행될 때만 실제로 삽입된다.

INSERT IGNORE INTO vehicle (vehicle_id, name, source, active, created_at, updated_at) VALUES
    ('REAL-F01', '실물 지게차 1호', 'REAL', TRUE, NOW(), NOW()),
    ('SIM-F01',  '시뮬레이션 지게차 1호', 'SIMULATION', TRUE, NOW(), NOW()),
    ('SIM-F02',  '시뮬레이션 지게차 2호', 'SIMULATION', TRUE, NOW(), NOW());

INSERT IGNORE INTO vehicle_current_status
    (vehicle_id, status, battery, position_x, position_y, heading, speed, message_at, received_at, updated_at) VALUES
    ('REAL-F01', 'IDLE',   NULL, NULL, NULL, NULL, NULL, NOW(), NOW(), NOW()),
    ('SIM-F01',  'ACTIVE', 82,   1.2,  3.4,  90,   0.4,  NOW(), NOW(), NOW()),
    ('SIM-F02',  'ERROR',  15,   NULL, NULL, NULL, NULL, NOW(), NOW(), NOW());
