package org.rx.util;

import lombok.SneakyThrows;

import javax.validation.*;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.rx.annotation.ValidRegex;
import org.rx.util.function.Func;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * http://www.cnblogs.com/pixy/p/5306567.html
 */
public class Validator {
    public static class RegexValidator implements ConstraintValidator<ValidRegex, String> {
        private ValidRegex validRegex;

        @Override
        public void initialize(ValidRegex validRegex) {
            this.validRegex = validRegex;
        }

        @Override
        public boolean isValid(String s, ConstraintValidatorContext context) {
            if (s == null) {
                return true;
            }

            Pattern p = Pattern.compile(validRegex.value().getRegexp(), Pattern.CASE_INSENSITIVE);
            return p.matcher(s).matches();
        }
    }

    private static javax.validation.Validator getValidator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }

    /**
     * 验证bean实体 @Valid deep valid
     *
     * @param bean
     */
    public static void validateBean(Object bean) {
        for (ConstraintViolation<Object> violation : getValidator().validate(bean)) {
            doThrow(violation);
        }
    }

    private static void doThrow(ConstraintViolation<Object> violation) {
        String pn = violation.getPropertyPath().toString(), vm = violation.getMessage();
        throw new ValidateException(pn, vm, String.format("%s.%s%s", violation.getRootBeanClass().getSimpleName(), pn, vm));
    }

    public static void validateConstructor(Constructor<?> member, Object[] parameterValues, boolean validateValues) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        Set<ConstraintViolation<Object>> result = executableValidator.validateConstructorParameters(member, parameterValues);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }
        if (validateValues && parameterValues != null) {
            for (Object parameterValue : parameterValues) {
                validateBean(parameterValue);
            }
        }
    }

    @SneakyThrows
    public static Object validateMethod(Method member, Object instance, Object[] parameterValues, boolean validateValues, Func<Object> proceed) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, member, parameterValues)) {
            doThrow(violation);
        }
        if (validateValues && parameterValues != null) {
            for (Object parameterValue : parameterValues) {
                validateBean(parameterValue);
            }
        }

        if (proceed == null) {
            return null;
        }
        Object retVal;
        for (ConstraintViolation<Object> violation : executableValidator.validateReturnValue(instance, member, retVal = proceed.invoke())) {
            doThrow(violation);
        }
        return retVal;
    }
}
