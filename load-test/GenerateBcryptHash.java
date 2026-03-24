package com.shop;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 부하 테스트 사용자 비밀번호 해시 생성
 *
 * 실행 방법:
 *   1) 프로젝트 루트에서: ./gradlew -q test --tests "com.shop.GenerateBcryptHash" 2>/dev/null
 *   2) 또는 main 메서드 직접 실행
 *
 * 출력된 해시를 setup-loadtest.sql의 bcrypt_hash 변수에 넣으세요.
 */
public class GenerateBcryptHash {

    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String raw = "test1234";
        String hash = encoder.encode(raw);
        System.out.println("=== BCrypt Hash Generator ===");
        System.out.println("  원본: " + raw);
        System.out.println("  해시: " + hash);
        System.out.println();
        System.out.println("setup-loadtest.sql에 아래 값을 복사하세요:");
        System.out.println("  bcrypt_hash VARCHAR(255) := '" + hash + "';");
    }
}
