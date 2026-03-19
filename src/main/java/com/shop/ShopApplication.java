package com.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// [Phase 19] @EnableScheduling을 SchedulingConfig로 이동하여 테스트 환경에서
// app.scheduling.enabled=false로 비활성화할 수 있도록 변경.
@SpringBootApplication
public class ShopApplication {

    public static void main(final String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
