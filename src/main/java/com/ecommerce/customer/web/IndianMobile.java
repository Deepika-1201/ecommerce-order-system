package com.ecommerce.customer.web;

import com.ecommerce.shared.MobileNumbers;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An Indian mobile number in any common notation; {@code null} is valid (use {@code @NotNull} to require one). */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = IndianMobile.Validator.class)
public @interface IndianMobile {

    String message() default "must be an Indian mobile number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<IndianMobile, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || MobileNumbers.normalize(value).isPresent();
        }
    }
}
