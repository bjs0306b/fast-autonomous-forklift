package com.fast.backend.traffic.config;

import com.fast.backend.traffic.domain.Track;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 순환로 기하 설정 ({@code traffic.loop.*}).
 *
 * <p>좌표를 코드에 박지 않는 이유: 맵이 바뀌면 F팀 쪽 {@code track.py} 와 함께 이 값도
 * 바뀌어야 하는데, 상수로 두면 재빌드·재배포가 필요하다.
 *
 * <p><b>단위는 시뮬 좌표계(20 × 30)다.</b> 실물의 10 배이고 백엔드는 변환하지 않는다
 * (F팀 {@code backend-mqtt-guide} §2).
 *
 * @param corners      모서리 목록. 반시계 순서로 적는다
 * @param cornerTolM   모서리 도달 판정(m). 이 안에 들어오면 지난 것으로 보고 다음 모서리를 준다
 * @param offTrackTolM 순환로 이탈 판정(m). 이보다 벗어난 차량은 차간 판정에서 뺀다
 * @param entryHeadwayM 합류 허가 최소 간격(m). 앞차가 이보다 가까우면 새 차를 안 내보낸다
 * @param entryIntervalMs 합류 허가 간격(ms). 한 대 내보낸 뒤 이만큼 기다린다
 * @param stallSec     정체 판정(초). 이만큼 진전이 없으면 목표를 다시 보낸다
 * @param stallMoveM   정체 판정 이동량(m). 이보다 적게 움직이면 "안 움직였다"로 본다
 */
@ConfigurationProperties(prefix = "traffic.loop")
public record LoopTrackProperties(
        List<Corner> corners,
        Double cornerTolM,
        Double offTrackTolM,
        Double entryHeadwayM,
        Long entryIntervalMs,
        Long stallSec,
        Double stallMoveM
) {

    /** F팀 규격 §1 의 반시계 순환로. 설정이 비면 이 값을 쓴다. */
    private static final List<Corner> DEFAULT_CORNERS = List.of(
            new Corner(15.5, 4.0),
            new Corner(15.5, 27.0),
            new Corner(5.0, 27.0),
            new Corner(5.0, 4.0));

    public LoopTrackProperties {
        if (corners == null || corners.isEmpty()) {
            corners = DEFAULT_CORNERS;
        }
        corners = List.copyOf(corners);
        if (cornerTolM == null || cornerTolM <= 0) cornerTolM = 2.0;
        if (offTrackTolM == null || offTrackTolM <= 0) offTrackTolM = 3.0;
        if (entryHeadwayM == null || entryHeadwayM <= 0) entryHeadwayM = 10.0;
        if (entryIntervalMs == null || entryIntervalMs <= 0) entryIntervalMs = 3000L;
        if (stallSec == null || stallSec <= 0) stallSec = 20L;
        if (stallMoveM == null || stallMoveM <= 0) stallMoveM = 0.3;
    }

    /**
     * 설정으로부터 {@link Track} 을 만든다.
     *
     * <p>모서리가 잘못돼 있으면 여기서 예외가 나고 <b>기동이 실패한다</b>. 일부러 그렇게 뒀다 —
     * 순환로가 깨진 채로 뜨면 차간 판정이 전부 엉뚱해지는데, 그건 로그만 보고는 알아채기 어렵다.
     */
    public Track toTrack() {
        List<double[]> points = new ArrayList<>(corners.size());
        for (Corner corner : corners) {
            points.add(new double[] {corner.x(), corner.y()});
        }
        return new Track(points);
    }

    /** 순환로 모서리 한 점(시뮬 좌표). */
    public record Corner(double x, double y) {
    }
}
