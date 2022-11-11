package org.rx.util;

import lombok.SneakyThrows;
import org.rx.annotation.ValidRegex;
import org.rx.core.Linq;
import org.rx.spring.SpringContext;
import org.rx.util.function.Func;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.rx.core.Extends.ifNull;

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
        return SpringContext.isInitiated() ? SpringContext.getBean(javax.validation.Validator.class) : Validation.buildDefaultValidatorFactory().getValidator();
    }

    /**
     * 验证bean实体 @Valid deep valid
     *
     * @param bean
     */
    public static void validateBean(Object bean) {
        Iterable<Object> list = ifNull(Linq.asIterable(bean, false), Collections.singletonList(bean));
        javax.validation.Validator validator = getValidator();
        for (Object b : list) {
            for (ConstraintViolation<Object> violation : validator.validate(b)) {
                doThrow(violation);
            }
        }
    }

    private static void doThrow(ConstraintViolation<Object> violation) {
        String pn = violation.getPropertyPath().toString(), vm = violation.getMessage();
        throw new ValidateException(pn, vm, String.format("%s.%s %s", violation.getRootBeanClass().getSimpleName(), pn, vm));
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
