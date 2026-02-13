package com.shop.global.security;

import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
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
}
