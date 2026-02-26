package com.shop.domain.user.repository;

import com.shop.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u JOIN FETCH u.tier WHERE u.userId = :userId")
    Optional<User> findByIdWithTier(@Param("userId") Long userId);

    @EntityGraph(attributePaths = "tier")
    Page<User> findAll(Pageable pageable);
}
