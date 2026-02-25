package com.shop.global.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class DuplicateConstraintMessageResolver {

    private static final String DEFAULT_MESSAGE = "중복된 데이터가 존재합니다. 다시 시도해주세요.";

    private static final Map<String, String> KEYWORD_TO_MESSAGE = new LinkedHashMap<>();

    static {
        KEYWORD_TO_MESSAGE.put("username", "이미 사용 중인 아이디입니다.");
        KEYWORD_TO_MESSAGE.put("user_name", "이미 사용 중인 아이디입니다.");
        KEYWORD_TO_MESSAGE.put("email", "이미 사용 중인 이메일입니다.");
        KEYWORD_TO_MESSAGE.put("e_mail", "이미 사용 중인 이메일입니다.");
    }

    public String resolve(DataIntegrityViolationException exception) {
        if (exception == null) {
            return DEFAULT_MESSAGE;
        }

        String raw = buildSearchableMessage(exception).toLowerCase(Locale.ROOT);

        for (Map.Entry<String, String> entry : KEYWORD_TO_MESSAGE.entrySet()) {
            if (raw.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return DEFAULT_MESSAGE;
    }

    private String buildSearchableMessage(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable cursor = throwable;

        while (cursor != null) {
            if (cursor.getMessage() != null) {
                builder.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }

        return builder.toString();
    }
}
