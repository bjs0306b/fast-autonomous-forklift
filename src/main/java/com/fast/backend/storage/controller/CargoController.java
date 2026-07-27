package com.fast.backend.storage.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.storage.dto.CargoCreateRequest;
import com.fast.backend.storage.dto.CargoResponse;
import com.fast.backend.storage.service.CargoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 화물 등록 API(prompt47.md 7장). 기존 VehicleController 스타일(ApiResponse 래핑) 준수. */
@RestController
@RequestMapping("/api/cargos")
public class CargoController {

    private final CargoService cargoService;

    public CargoController(CargoService cargoService) {
        this.cargoService = cargoService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CargoResponse>> register(@Valid @RequestBody CargoCreateRequest request) {
        CargoResponse response = cargoService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
