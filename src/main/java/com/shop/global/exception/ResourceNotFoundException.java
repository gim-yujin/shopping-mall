package com.shop.global.exception;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource, Object id) {
        super("NOT_FOUND", resource + "을(를) 찾을 수 없습니다. ID: " + id);
    }
}
