package com.ecommerce.customer.web;

import com.ecommerce.shared.IndianState;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A current GST state code, as listed by {@code GET /v1/states}; {@code null} is valid. */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = GstStateCode.Validator.class)
public @interface GstStateCode {

    String message() default "must be a GST state code listed by /v1/states";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<GstStateCode, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || IndianState.fromCode(value).isPresent();
        }
    }
}
