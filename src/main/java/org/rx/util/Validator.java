package org.rx.util;

import lombok.SneakyThrows;

import javax.validation.*;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.rx.annotation.ValidRegex;
import org.rx.core.NQuery;
import org.rx.util.function.Func;

import java.util.List;
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
        List<Object> list = NQuery.asList(bean, false);
        if (list.isEmpty()) {
            list.add(bean);
        }
        javax.validation.Validator validator = getValidator();
        for (Object b : list) {
            for (ConstraintViolation<Object> violation : validator.validate(b)) {
                doThrow(violation);
            }
        }
    }

    private static void doThrow(ConstraintViolation<Object> violation) {
        String pn = violation.getPropertyPath().toString(), vm = violation.getMessage();
        throw new ValidateException(pn, vm, String.format("%s.%s%s", violation.getRootBeanClass().getSimpleName(), pn, vm));
    }

    public static void validateConstructor(Constructor<?> member, Object instance, Object[] parameterValues) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        Set<ConstraintViolation<Object>> result = executableValidator.validateConstructorParameters(member, parameterValues);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }

        executableValidator.validateConstructorReturnValue(member, instance);
    }

    @SneakyThrows
    public static Object validateMethod(Method member, Object instance, Object[] parameterValues, Func<Object> proceed) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        //@Valid deep validateValues
        for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, member, parameterValues)) {
            doThrow(violation);
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
