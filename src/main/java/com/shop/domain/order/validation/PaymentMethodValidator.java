package com.shop.domain.order.validation;

import com.shop.domain.order.entity.PaymentMethod;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PaymentMethodValidator implements ConstraintValidator<ValidPaymentMethod, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return PaymentMethod.fromCode(value).isPresent();
    }
}
