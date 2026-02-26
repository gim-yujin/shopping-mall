package com.shop.domain.order.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PaymentMethodValidator.class)
public @interface ValidPaymentMethod {
    String message() default "지원하지 않는 결제수단";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
