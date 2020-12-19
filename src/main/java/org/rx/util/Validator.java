package org.rx.util;

import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;

import javax.validation.*;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

//import org.rx.annotation.EnableValid;
import org.rx.annotation.ValidRegex;
import org.rx.core.StringBuilder;
import org.rx.spring.LogInterceptor;

import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * http://www.cnblogs.com/pixy/p/5306567.html
 */
public class Validator  {
    public static class RegexValidator implements ConstraintValidator<ValidRegex, String> {
        private ValidRegex validRegex;

        @Override
        public void initialize(ValidRegex validRegex) {
            this.validRegex = validRegex;
        }

        @Override
        public boolean isValid(String s, ConstraintValidatorContext constraintValidatorContext) {
            if (s == null) {
                return true;
            }

            Pattern p = Pattern.compile(validRegex.value().getRegexp(), Pattern.CASE_INSENSITIVE);
            return p.matcher(s).matches();
        }
    }

    /**
     * 验证bean实体 @Valid deep valid
     *
     * @param bean
     */
    public static void validateBean(Object bean) {
        javax.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        for (ConstraintViolation<Object> violation : validator.validate(bean)) {
            doThrow(violation);
        }
    }

    private static boolean hasFlags(int flags, int values) {
        return (flags & values) == values;
    }

    private static void doThrow(ConstraintViolation<Object> violation) {
        String pn = violation.getPropertyPath().toString(), vm = violation.getMessage();
        throw new ValidateException(pn, vm, String.format("%s.%s%s", violation.getRootBeanClass().getSimpleName(), pn, vm));
    }

    public static void validateConstructor(Constructor member, Object[] parameterValues, boolean validateValues) {
        javax.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator executableValidator = validator.forExecutables();
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

    public static Object validateMethod(Method member, Object instance, Object[] parameterValues, boolean validateValues, Supplier<Object> delayReturnValue) {
        javax.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator executableValidator = validator.forExecutables();
        for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, member, parameterValues)) {
            doThrow(violation);
        }
        if (validateValues && parameterValues != null) {
            for (Object parameterValue : parameterValues) {
                validateBean(parameterValue);
            }
        }

        if (delayReturnValue == null) {
            return null;
        }
        Object retVal;
        for (ConstraintViolation<Object> violation : executableValidator.validateReturnValue(instance, member, retVal = delayReturnValue.get())) {
            doThrow(violation);
        }
        return retVal;
    }

//    /**
//     * Annotation expression只对method有效
//     *
//     * @param joinPoint
//     * @param msg
//     * @return
//     * @throws Throwable
//     */
//    @SneakyThrows
//    @Override
//    protected Object onProcess(ProceedingJoinPoint joinPoint, StringBuilder msg) {
//        Class targetType = joinPoint.getTarget().getClass();
//        Signature signature = joinPoint.getSignature();
//        Executable member;
//        if (signature instanceof ConstructorSignature) {
//            member = ((ConstructorSignature) signature).getConstructor();
//        } else {
//            member = ((MethodSignature) signature).getMethod();
//        }
//
//        msg.setPrefix(String.format("[Valid] %s.%s ", targetType.getSimpleName(), signature.getName()));
//        EnableValid attr = member.getAnnotation(EnableValid.class);
//        if (attr == null) {
//            attr = (EnableValid) targetType.getAnnotation(EnableValid.class);
//            if (attr == null) {
//                msg.appendLine("skip validate..");
//                return joinPoint.proceed();
//            }
//        }
//
//        int flags = attr.value();
//        boolean validateValues = hasFlags(flags, EnableValid.ParameterValues);
//        if (hasFlags(flags, EnableValid.Method)) {
//            if (signature instanceof ConstructorSignature) {
//                ConstructorSignature cs = (ConstructorSignature) signature;
//                validateConstructor(cs.getConstructor(), joinPoint.getArgs(), validateValues);
//                return super.onProcess(joinPoint, msg);
//            }
//
//            MethodSignature ms = (MethodSignature) signature;
//            return validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), validateValues, () -> super.onProcess(joinPoint, msg));
//        }
//
//        if (validateValues) {
//            for (Object parameterValue : joinPoint.getArgs()) {
//                validateBean(parameterValue);
//            }
//        }
//
//        msg.appendLine("validate ok..").setPrefix(null);
//        return super.onProcess(joinPoint, msg);
//    }
//
//    @Override
//    protected Object onException(Signature signature, Exception ex, StringBuilder msg) throws Throwable {
//        msg.appendLine("validate fail %s..", ex.getMessage());
//        throw ex;
//    }
}
