package com.smartparking.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/** The UTF-8 encoding of the value must not exceed {@link #value()} bytes (null is valid). */
@Constraint(validatedBy = MaxBytes.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxBytes {

    int value();

    String message() default "must be at most {value} bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxBytes, CharSequence> {

        private int max;

        @Override
        public void initialize(MaxBytes annotation) {
            this.max = annotation.value();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
