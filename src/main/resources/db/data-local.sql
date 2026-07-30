-- 로컬 개발 전용 더미 데이터. application-local.yml 에서만 로드된다.
--
-- FR-202 최종 스키마(prompt85) 기준으로 갱신했다.
--   · vehicle.source / vehicle_type 컬럼이 사라져 더 이상 넣지 않는다(실물/시뮬 구분 불가).
--   · vehicle_current_status 에는 message_at / updated_at 이 없다.
--
-- INSERT IGNORE 를 쓰는 이유: 로컬에서 애플리케이션을 자주 재시작하는데 이 파일이 매번 다시
-- 실행돼도(mode=always) PK 중복으로 기동이 실패하지 않도록 하기 위함이다(멱등성).

INSERT IGNORE INTO vehicle (vehicle_id, name, active, created_at, updated_at) VALUES
    ('REAL-F01', '실물 지게차 1호', TRUE, NOW(6), NOW(6)),
    ('SIM-F01',  '시뮬레이션 지게차 1호', TRUE, NOW(6), NOW(6)),
    ('SIM-F02',  '시뮬레이션 지게차 2호', TRUE, NOW(6), NOW(6));

INSERT IGNORE INTO vehicle_current_status
    (vehicle_id, status, battery, position_x, position_y, heading, speed, received_at) VALUES
    ('REAL-F01', 'IDLE',   NULL, NULL, NULL, NULL, NULL, NOW(6)),
    ('SIM-F01',  'ACTIVE', 82,   1.2,  3.4,  90,   0.4,  NOW(6)),
    ('SIM-F02',  'ERROR',  15,   NULL, NULL, NULL, NULL, NOW(6));
