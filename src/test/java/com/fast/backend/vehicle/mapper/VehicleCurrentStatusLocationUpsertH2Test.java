package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code updateLocation} 의 <b>조건부 upsert 가 실제 DB 에서 동작하는지</b> 검증한다
 * (prompt84 → prompt85 에서 컬럼 삭제로 폐기 → prompt86 A안으로 복구).
 *
 * <p>mock 으로는 SQL 동시성 안전성을 증명할 수 없어 실제 H2(MySQL 호환 모드)에 붙는다. 다만
 * {@code @SpringBootTest} 를 쓰지 않고 <b>MyBatis + H2 를 직접 조립</b>한다 — 이 저장소의 통합 테스트
 * 일부가 아직 FR-202 전환 중이라 컨텍스트 기동에 의존하지 않기 위해서다. 테이블은 이 클래스 안에서
 * Java 문자열로 만들며 <b>새 .sql 파일을 만들지 않는다</b>. 컬럼 정의는 {@code schema.sql} 의
 * {@code vehicle_current_status} 와 같게 맞췄다.
 *
 * <p>Mapper XML 은 운영과 <b>같은 파일</b>을 읽는다 — 테스트용 SQL 을 따로 쓰면 정작 운영 SQL 이
 * 검증되지 않는다.
 */
class VehicleCurrentStatusLocationUpsertH2Test {

    private static final String VEHICLE_ID = "REAL-F01";
    private static final LocalDateTime STORED_AT = LocalDateTime.of(2026, 7, 27, 10, 0, 0);
    private static final LocalDateTime NEWER_AT = LocalDateTime.of(2026, 7, 27, 10, 0, 2);
    private static final LocalDateTime OLDER_AT = LocalDateTime.of(2026, 7, 27, 10, 0, 1);

    private SqlSessionFactory sqlSessionFactory;
    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:loc-upsert-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            // schema.sql 의 vehicle_current_status 와 같은 컬럼 구성(FK/부모 테이블은 이 테스트 범위 밖).
            st.execute("""
                    CREATE TABLE vehicle_current_status (
                        vehicle_id       VARCHAR(50)  NOT NULL PRIMARY KEY,
                        status           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
                        battery          INT          NULL,
                        position_x       DOUBLE       NULL,
                        position_y       DOUBLE       NULL,
                        heading          DOUBLE       NULL,
                        speed            DOUBLE       NULL,
                        fork_height      DOUBLE       NULL,
                        fork_state       VARCHAR(20)  NULL,
                        fork_error_code  VARCHAR(50)  NULL,
                        has_cargo        BOOLEAN      NULL,
                        cargo_id         VARCHAR(50)  NULL,
                        footprint_length DOUBLE       NULL,
                        footprint_width  DOUBLE       NULL,
                        message_at       DATETIME(6)  NULL,
                        received_at      DATETIME(6)  NOT NULL
                    )
                    """);
        }

        Configuration configuration = new Configuration(
                new Environment("h2", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        String resource = "mapper/VehicleCurrentStatusMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 테스트 10: 서비스 검사를 통과해 늦게 도착한 오래된 메시지를 SQL 이 막는다. */
    @Test
    void olderMessage_doesNotOverwriteNewerRow() {
        seedRow(STORED_AT, 1.0, 1.0, 0.0);

        updateLocation(5.0, 6.0, 90.0, NEWER_AT, NEWER_AT);
        updateLocation(9.9, 9.9, 180.0, OLDER_AT, OLDER_AT);

        VehicleCurrentStatus after = find();
        assertThat(after.getPositionX()).isEqualTo(5.0);
        assertThat(after.getPositionY()).isEqualTo(6.0);
        assertThat(after.getHeading()).isEqualTo(90.0);
        assertThat(after.getMessageAt()).isEqualTo(NEWER_AT);
    }

    /** 테스트 11: 오래된 메시지는 received_at 도 바꾸지 못한다. */
    @Test
    void olderMessage_doesNotTouchReceivedAt() {
        seedRow(STORED_AT, 1.0, 1.0, 0.0);
        updateLocation(5.0, 6.0, 90.0, NEWER_AT, NEWER_AT);
        LocalDateTime receivedAfterNewer = find().getReceivedAt();

        updateLocation(9.9, 9.9, 180.0, OLDER_AT, LocalDateTime.of(2030, 1, 1, 0, 0));

        assertThat(find().getReceivedAt()).isEqualTo(receivedAfterNewer);
    }

    /** 같은 messageAt 재전송(QoS 1 중복)도 값을 바꾸지 못한다. */
    @Test
    void duplicateMessageAt_doesNotOverwrite() {
        seedRow(STORED_AT, 1.0, 1.0, 0.0);
        updateLocation(5.0, 6.0, 90.0, NEWER_AT, NEWER_AT);

        updateLocation(7.7, 7.7, 270.0, NEWER_AT, NEWER_AT);

        assertThat(find().getPositionX()).isEqualTo(5.0);
        assertThat(find().getHeading()).isEqualTo(90.0);
    }

    /** 테스트 12: 위치 갱신이 status/battery/speed/fork/Isaac 확장 필드를 건드리지 않는다. */
    @Test
    void locationUpdate_preservesOtherStatusColumns() {
        seedRow(STORED_AT, 1.0, 1.0, 0.0);

        updateLocation(5.0, 6.0, 90.0, NEWER_AT, NEWER_AT);

        VehicleCurrentStatus after = find();
        assertThat(after.getStatus()).isEqualTo(VehicleStatus.MOVING);
        assertThat(after.getBattery()).isEqualTo(77);
        assertThat(after.getSpeed()).isEqualTo(0.4);
        assertThat(after.getForkHeight()).isEqualTo(1.2);
        assertThat(after.getForkState()).isEqualTo("BOTTOM");
        assertThat(after.getHasCargo()).isTrue();
        assertThat(after.getCargoId()).isEqualTo("CARGO-1");
        assertThat(after.getFootprintLength()).isEqualTo(2.0);
        assertThat(after.getFootprintWidth()).isEqualTo(1.0);
    }

    /** 테스트 8: 행이 없으면 INSERT 되고, 그때만 status='UNKNOWN' 이다. */
    @Test
    void missingRow_isInsertedWithUnknownStatus() {
        updateLocation(1.0, 2.0, 0.0, NEWER_AT, NEWER_AT);

        VehicleCurrentStatus after = find();
        assertThat(after.getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
        assertThat(after.getPositionX()).isEqualTo(1.0);
        assertThat(after.getMessageAt()).isEqualTo(NEWER_AT);
    }

    /** 저장된 message_at 이 null 이면(위치를 한 번도 못 받은 행) 정상 저장된다. */
    @Test
    void storedMessageAtNull_isPersisted() {
        seedRow(null, null, null, null);

        updateLocation(3.0, 4.0, 45.0, NEWER_AT, NEWER_AT);

        assertThat(find().getPositionX()).isEqualTo(3.0);
        assertThat(find().getMessageAt()).isEqualTo(NEWER_AT);
    }

    /**
     * 테스트 23: newer(10:00:02)와 older(10:00:01)가 거의 동시에 도착해도 최종 값은 항상 newer 다.
     * 두 스레드를 CountDownLatch 로 같은 순간에 풀고, 순서와 무관하게 결과가 같은지 본다.
     */
    @Test
    void concurrentUpdates_endUpWithNewestMessage() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            recreateRow(STORED_AT);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> runAfter(start, done, () -> updateLocation(5.0, 6.0, 90.0, NEWER_AT, NEWER_AT)));
                pool.submit(() -> runAfter(start, done, () -> updateLocation(9.9, 9.9, 180.0, OLDER_AT, OLDER_AT)));
                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            VehicleCurrentStatus after = find();
            assertThat(after.getMessageAt()).isEqualTo(NEWER_AT);
            assertThat(after.getPositionX()).isEqualTo(5.0);
            assertThat(after.getPositionY()).isEqualTo(6.0);
            assertThat(after.getHeading()).isEqualTo(90.0);
        }
    }

    private void runAfter(CountDownLatch start, CountDownLatch done, Runnable action) {
        try {
            start.await();
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    private void updateLocation(Double x, Double y, Double heading, LocalDateTime messageAt,
            LocalDateTime receivedAt) {
        // 세션마다 별도 커넥션 — 동시성 테스트에서 두 스레드가 같은 세션을 공유하면 의미가 없다.
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(VehicleCurrentStatusMapper.class)
                    .updateLocation(VEHICLE_ID, x, y, heading, messageAt, receivedAt);
        }
    }

    private VehicleCurrentStatus find() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.getMapper(VehicleCurrentStatusMapper.class)
                    .findByVehicleId(VEHICLE_ID)
                    .orElseThrow();
        }
    }

    private void recreateRow(LocalDateTime messageAt) {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            st.execute("DELETE FROM vehicle_current_status");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        seedRow(messageAt, 1.0, 1.0, 0.0);
    }

    /** 다른 토픽이 이미 채워 둔 행을 흉내 낸다 — 위치 갱신이 이 값들을 지우면 안 된다. */
    private void seedRow(LocalDateTime messageAt, Double x, Double y, Double heading) {
        String sql = """
                INSERT INTO vehicle_current_status
                    (vehicle_id, status, battery, position_x, position_y, heading, speed,
                     fork_height, fork_state, fork_error_code, has_cargo, cargo_id,
                     footprint_length, footprint_width, message_at, received_at)
                VALUES (?, 'MOVING', 77, ?, ?, ?, 0.4, 1.2, 'BOTTOM', NULL, TRUE, 'CARGO-1', 2.0, 1.0, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, VEHICLE_ID);
            ps.setObject(2, x);
            ps.setObject(3, y);
            ps.setObject(4, heading);
            ps.setObject(5, messageAt);
            ps.setObject(6, messageAt == null ? STORED_AT : messageAt);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
