package com.fast.backend.storage.domain;

/**
 * 팔레트 처리 상태(prompt46.md 4장).
 */
public enum PalletStatus {
    DETECTED,
    WAITING,
    ASSIGNED,
    PICKED_UP,
    TRANSPORTING,
    STORED,
    FAILED
}
