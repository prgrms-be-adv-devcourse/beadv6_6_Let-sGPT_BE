package com.openat.order.infrastructure.client;

public record ProductErrorResponse(String code, String error, String message) {

    public String failCode() {
        if (code != null && !code.isBlank()) {
            return code;
        }
        return error;
    }
}
