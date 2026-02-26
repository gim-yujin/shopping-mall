package com.shop.global.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;

/**
 * [BUG FIX] Serializable 구현 추가.
 * 현재 Caffeine(in-process) 캐시에서는 직렬화 없이 동작하지만,
 * 향후 Redis 등 분산 캐시로 전환 시 세션/캐시에 저장된 UserDetails가
 * 직렬화되지 않아 NotSerializableException이 발생할 수 있다.
 * 미리 Serializable을 구현하여 캐시 전환 시 장애를 예방한다.
 */
public class CustomUserPrincipal implements UserDetails, Serializable {

    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final String username;
    private final String password;
    private final String name;
    private final String role;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserPrincipal(Long userId, String username, String password,
                                String name, String role,
                                Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.name = name;
        this.role = role;
        this.authorities = authorities;
    }

    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getRole() { return role; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
