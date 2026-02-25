package com.shop.global.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateConstraintMessageResolverTest {

    private final DuplicateConstraintMessageResolver resolver = new DuplicateConstraintMessageResolver();

    @Test
    void resolve_usernameConstraint_returnsUsernameMessage() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"users_username_key\"");

        assertThat(resolver.resolve(exception)).isEqualTo("이미 사용 중인 아이디입니다.");
    }

    @Test
    void resolve_renamedConstraintStillContainingUsername_returnsUsernameMessage() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_users_login_username_v2\"");

        assertThat(resolver.resolve(exception)).isEqualTo("이미 사용 중인 아이디입니다.");
    }

    @Test
    void resolve_nestedCauseContainingEmail_returnsEmailMessage() {
        RuntimeException cause = new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"users_email_unique_2026\"");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("insert failed", cause);

        assertThat(resolver.resolve(exception)).isEqualTo("이미 사용 중인 이메일입니다.");
    }

    @Test
    void resolve_unknownConstraint_returnsDefaultMessage() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_profiles_nickname\"");

        assertThat(resolver.resolve(exception)).isEqualTo("중복된 데이터가 존재합니다. 다시 시도해주세요.");
    }
}
