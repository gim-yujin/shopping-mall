package com.shop.domain.user.service;

import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserTierRepository tierRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, UserTierRepository tierRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tierRepository = tierRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User signup(SignupRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException("DUPLICATE", "이미 사용 중인 아이디입니다.");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException("DUPLICATE", "이미 사용 중인 이메일입니다.");
        }

        UserTier defaultTier = tierRepository.findByTierLevel(1)
                .orElseThrow(() -> new ResourceNotFoundException("기본 등급", 1));

        User user = new User(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name(),
                request.phone()
        );
        user.setTier(defaultTier);

        return userRepository.save(user);
    }

    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", username));
    }

    @Transactional
    public void updateProfile(Long userId, String name, String phone, String email) {
        User user = findById(userId);
        if (!user.getEmail().equals(email) && userRepository.existsByEmail(email)) {
            throw new BusinessException("DUPLICATE", "이미 사용 중인 이메일입니다.");
        }
        user.updateProfile(name, phone, email);
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = findById(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("INVALID_PASSWORD", "현재 비밀번호가 일치하지 않습니다.");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
    }
}
