package com.fast.backend.storage.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.storage.dto.PalletCreateRequest;
import com.fast.backend.storage.dto.PalletResponse;
import com.fast.backend.storage.service.PalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 팔레트 등록 API(prompt47.md 7장). */
@RestController
@RequestMapping("/api/pallets")
public class PalletController {

    private final PalletService palletService;

    public PalletController(PalletService palletService) {
        this.palletService = palletService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PalletResponse>> register(@Valid @RequestBody PalletCreateRequest request) {
        PalletResponse response = palletService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
