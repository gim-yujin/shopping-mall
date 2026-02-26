package com.shop.domain.user.service;

import com.shop.domain.user.dto.SignupRequest;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^$|^01[0-9]-?\\d{3,4}-?\\d{4}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,100}$");

    private final UserRepository userRepository;
    private final UserTierRepository tierRepository;
    private final PasswordEncoder passwordEncoder;
    private final CacheManager cacheManager;

    private static final String USER_DETAILS_CACHE = "userDetails";

    public UserService(UserRepository userRepository, UserTierRepository tierRepository,
                       PasswordEncoder passwordEncoder,
                       CacheManager cacheManager) {
        this.userRepository = userRepository;
        this.tierRepository = tierRepository;
        this.passwordEncoder = passwordEncoder;
        this.cacheManager = cacheManager;
    }

    @Transactional
    public User signup(SignupRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException("DUPLICATE", "이미 사용 중인 아이디입니다.");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException("DUPLICATE", "이미 사용 중인 이메일입니다.");
        }
        if (request.password() == null || !PASSWORD_PATTERN.matcher(request.password()).matches()) {
            throw new BusinessException("INVALID_INPUT", "비밀번호는 영문, 숫자, 특수문자를 포함해야 합니다.");
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
        return userRepository.findByIdWithTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", username));
    }

    @Transactional
    public void updateProfile(Long userId, String name, String phone, String email) {
        validateProfileInput(name, phone, email);

        User user = findById(userId);
        if (!user.getEmail().equals(email) && userRepository.existsByEmail(email)) {
            throw new BusinessException("DUPLICATE", "이미 사용 중인 이메일입니다.");
        }
        user.updateProfile(name.trim(), normalizePhone(phone), email.trim());
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        validatePasswordInput(currentPassword, newPassword);

        User user = findById(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("INVALID_PASSWORD", "현재 비밀번호가 일치하지 않습니다.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("SAME_PASSWORD", "새 비밀번호는 기존 비밀번호와 달라야 합니다.");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        evictUserDetailsCache(user.getUsername());
    }

    private void evictUserDetailsCache(String username) {
        Cache cache = cacheManager.getCache(USER_DETAILS_CACHE);
        if (cache == null || username == null) {
            return;
        }
        cache.evict(username);
    }

    private void validateProfileInput(String name, String phone, String email) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("INVALID_INPUT", "이름을 입력해주세요.");
        }
        if (email == null || email.trim().isEmpty()) {
            throw new BusinessException("INVALID_INPUT", "이메일을 입력해주세요.");
        }
        if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new BusinessException("INVALID_INPUT", "올바른 이메일 형식이 아닙니다.");
        }

        String normalizedPhone = normalizePhone(phone);
        if (!PHONE_PATTERN.matcher(normalizedPhone).matches()) {
            throw new BusinessException("INVALID_INPUT", "연락처는 010-1234-5678 형식으로 입력해주세요.");
        }
    }

    private void validatePasswordInput(String currentPassword, String newPassword) {
        if (currentPassword == null || currentPassword.trim().isEmpty()) {
            throw new BusinessException("INVALID_INPUT", "현재 비밀번호를 입력해주세요.");
        }
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new BusinessException("INVALID_INPUT", "새 비밀번호를 입력해주세요.");
        }
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new BusinessException("INVALID_INPUT", "비밀번호는 영문, 숫자, 특수문자를 포함해 8자 이상이어야 합니다.");
        }
    }

    private String normalizePhone(String phone) {
        return phone == null ? "" : phone.trim();
    }
}
