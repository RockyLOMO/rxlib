package org.rx.validator;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ui.Model;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.rx.common.Contract.toJSONString;
import static org.rx.common.Contract.wrapCause;
import static org.rx.util.App.logInfo;

/**
 * http://www.cnblogs.com/pixy/p/5306567.html
 */
public class ValidateUtil {
    private static final List<Class> SkipTypes = Arrays.asList(ServletRequest.class, ServletResponse.class,
            Model.class);

    /**
     * 验证bean实体 @Valid deep valid
     *
     * @param bean
     */
    public static void validateBean(Object bean) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        for (ConstraintViolation<Object> violation : validator.validate(bean)) {
            doThrow(violation);
        }
    }

    private static boolean hasFlags(int flags, int values) {
        return (flags & values) == values;
    }

    private static void doThrow(ConstraintViolation<Object> violation) {
        String pn = violation.getPropertyPath().toString(), vm = violation.getMessage();
        throw new ConstraintException(pn, vm,
                String.format("%s.%s%s", violation.getRootBeanClass().getSimpleName(), pn, vm));
    }

    public static void validateConstructor(Constructor member, Object[] parameterValues, boolean validateValues) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator executableValidator = validator.forExecutables();
        Set<ConstraintViolation<Object>> result = executableValidator.validateConstructorParameters(member,
                parameterValues);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }
        if (validateValues && parameterValues != null) {
            for (Object parameterValue : parameterValues) {
                validateBean(parameterValue);
            }
        }
    }

    public static Object validateMethod(Method member, Object instance, Object[] parameterValues,
                                        boolean validateValues, Function<Object, Object> returnValueFuc) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator executableValidator = validator.forExecutables();
        for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, member,
                parameterValues)) {
            doThrow(violation);
        }
        if (validateValues && parameterValues != null) {
            for (Object parameterValue : parameterValues) {
                validateBean(parameterValue);
            }
        }

        if (returnValueFuc == null) {
            return null;
        }
        Object retVal;
        for (ConstraintViolation<Object> violation : executableValidator.validateReturnValue(instance, member,
                retVal = returnValueFuc.apply(null))) {
            doThrow(violation);
        }
        return retVal;
    }

    /**
     * Annotation expression只对method有效
     * 
     * @param joinPoint
     * @return
     * @throws Throwable
     */
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Class targetType = joinPoint.getTarget().getClass();
        Signature signature = joinPoint.getSignature();
        Executable member;
        if (signature instanceof ConstructorSignature) {
            member = ((ConstructorSignature) signature).getConstructor();
        } else {
            member = ((MethodSignature) signature).getMethod();
        }

        StringBuilder msg = new StringBuilder();
        msg.setPrefix(String.format("[ValidateAround] %s.%s ", targetType.getSimpleName(), signature.getName()));
        try {
            msg.appendLine("begin check..");
            ValidFlag attr = member.getAnnotation(ValidFlag.class);
            if (attr == null) {
                attr = (ValidFlag) targetType.getAnnotation(ValidFlag.class);
                if (attr == null) {
                    msg.appendLine("exit..");
                    return joinPoint.proceed();
                }
            }
            List args = Arrays.stream(joinPoint.getArgs())
                    .filter(p -> !(SkipTypes.stream().anyMatch(p2 -> p2.isInstance(p)))).collect(Collectors.toList());
            msg.appendLine("begin validate args=%s..", toJSONString(args));

            int flags = attr.value();
            boolean validateValues = hasFlags(flags, ValidFlag.ParameterValues);
            if (hasFlags(flags, ValidFlag.Method)) {
                if (signature instanceof ConstructorSignature) {
                    ConstructorSignature cs = (ConstructorSignature) signature;
                    validateConstructor(cs.getConstructor(), joinPoint.getArgs(), validateValues);
                    return joinPoint.proceed();
                }

                MethodSignature ms = (MethodSignature) signature;
                return validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), validateValues, p -> {
                    try {
                        return joinPoint.proceed();
                    } catch (Throwable ex) {
                        throw wrapCause(ex);
                    }
                });
            }

            if (validateValues) {
                for (Object parameterValue : joinPoint.getArgs()) {
                    validateBean(parameterValue);
                }
            }
            return joinPoint.proceed();
        } catch (Exception ex) {
            msg.appendLine("validate fail %s..", ex.getMessage());
            throw ex;
        } finally {
            msg.appendLine("end validate..");
            logInfo(msg.toString());
        }
    }
}
