package com.fast.backend.storage.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.storage.dto.CargoResponse;
import com.fast.backend.storage.service.CargoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 화물과 대기 운반 작업을 한 번에 등록하는 입하 API. */
@RestController
@RequestMapping("/api/cargos")
public class CargoController {

    private final CargoService cargoService;

    public CargoController(CargoService cargoService) {
        this.cargoService = cargoService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CargoResponse>> register() {
        CargoResponse response = cargoService.register();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
