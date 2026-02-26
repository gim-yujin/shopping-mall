package com.shop.domain.user.repository;

import com.shop.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u JOIN FETCH u.tier WHERE u.userId = :userId")
    Optional<User> findByIdWithTier(@Param("userId") Long userId);

    @EntityGraph(attributePaths = "tier")
    Page<User> findAll(Pageable pageable);

    /**
     * [BUG FIX] Keyset(cursor) 기반 사용자 청크 조회.
     * offset 페이징은 OFFSET 값이 커질수록 PostgreSQL이 해당 행 수만큼 스캔 후 버려야 하므로
     * 100만 사용자 기준 마지막 페이지에서 수십 초가 걸릴 수 있다.
     * keyset 방식은 PK 인덱스를 타므로 어느 페이지든 일정한 O(limit) 성능을 보장한다.
     */
    @EntityGraph(attributePaths = "tier")
    @Query("SELECT u FROM User u WHERE u.userId > :lastUserId ORDER BY u.userId ASC")
    List<User> findUsersAfterIdWithTier(@Param("lastUserId") Long lastUserId, Pageable pageable);
}
