-- username 대소문자 비구분 유니크 정책 적용
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username_lower ON users (LOWER(username));
