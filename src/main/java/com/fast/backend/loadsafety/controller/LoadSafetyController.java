package com.fast.backend.loadsafety.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.loadsafety.dto.LoadSafetyResponse;
import com.fast.backend.loadsafety.service.LoadSafetyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 적재 화물 안전 상태 조회 API(prompt63.md 3장 2번). 기존 {@code /api/vehicles} base path를 공유하되
 * 적재 안전 전용 엔드포인트만 담당한다({@code VehicleMonitoringController}와 같은 방식, 경로 충돌 없음).
 *
 * <p>조회 전용이다 — 이 도메인의 <b>쓰기 경로는 MQTT 하나뿐</b>이며 REST로 적재 안전 상태를 주입하는
 * 엔드포인트는 두지 않는다. 화면이나 외부 도구가 위험 단계를 임의로 써넣을 수 있으면 "비전이 판정한
 * 값"이라는 계약이 깨진다.
 */
@RestController
@RequestMapping("/api/vehicles")
public class LoadSafetyController {

    private final LoadSafetyService loadSafetyService;

    public LoadSafetyController(LoadSafetyService loadSafetyService) {
        this.loadSafetyService = loadSafetyService;
    }

    /** 활성 차량 전체의 최신 적재 안전 상태. 미수신 차량은 목록에 포함되지 않는다. */
    @GetMapping("/load-safety/latest")
    public ApiResponse<List<LoadSafetyResponse>> latestAll() {
        return ApiResponse.success(loadSafetyService.getAllLatest());
    }

    /**
     * 차량 최신 적재 안전 상태. 한 번도 수신하지 못한 차량은 <b>200 + {@code data:null}</b>이다
     * (미수신은 오류가 아니다 — {@code /location/latest}와 동일한 방침). 등록되지 않은 차량은 404다.
     */
    @GetMapping("/{vehicleId}/load-safety/latest")
    public ApiResponse<LoadSafetyResponse> latest(@PathVariable String vehicleId) {
        return ApiResponse.success(loadSafetyService.getLatest(vehicleId));
    }
}
