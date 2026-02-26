package com.shop.global.security;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 사용자 인증 정보 조회.
     * @Cacheable: 동일 username의 반복 로그인 시 DB 쿼리를 건너뛰어
     *   커넥션 풀 경합을 줄임. BCrypt 검증은 여전히 매번 실행됨.
     * TTL 5분 (CacheConfig 공통) → 비밀번호 변경 시 최대 5분 후 반영.
     */
    @Override
    @Cacheable(value = "userDetails", key = "(#username == null ? '' : #username.trim().toLowerCase())")
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalizedUsername = normalizeUsername(username);
        User user = userRepository.findByUsernameIgnoreCase(normalizedUsername)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username));

        if (!user.getIsActive()) {
            throw new UsernameNotFoundException("비활성화된 계정입니다.");
        }

        return new CustomUserPrincipal(
                user.getUserId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getName(),
                user.getRole(),
                Collections.singletonList(new SimpleGrantedAuthority(user.getRole()))
        );
    }


    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
