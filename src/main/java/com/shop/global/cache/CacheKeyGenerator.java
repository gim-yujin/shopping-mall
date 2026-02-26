package com.shop.global.cache;

import org.springframework.data.domain.Pageable;

public final class CacheKeyGenerator {

    private CacheKeyGenerator() {
    }

    public static String pageable(Pageable pageable) {
        return pageable.getPageNumber() + ":" + pageable.getPageSize() + ":" + pageable.getSort().toString();
    }

    public static String pageableWithPrefix(Object prefix, Pageable pageable) {
        return prefix + ":" + pageable(pageable);
    }
}

