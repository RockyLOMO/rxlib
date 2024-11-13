package org.rx.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.rx.annotation.ValidRegex;
import org.rx.core.Arrays;
import org.rx.core.Linq;
import org.rx.core.Reflects;
import org.rx.util.function.Func;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;
import sun.reflect.generics.reflectiveObjects.TypeVariableImpl;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.ifNull;

/**
 * http://www.cnblogs.com/pixy/p/5306567.html
 *
 * @Valid deep valid
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

    static final javax.validation.Validator DEFAULT = Validation.buildDefaultValidatorFactory().getValidator();

    private static javax.validation.Validator getValidator() {
        return DEFAULT;
    }

    public static void validateBean(Object bean) {
        Iterable<Object> beans = ifNull(Linq.asIterable(bean, false), Collections.singletonList(bean));
        javax.validation.Validator validator = getValidator();
        for (Object b : beans) {
            for (ConstraintViolation<Object> violation : validator.validate(b)) {
                doThrow(violation);
            }
        }
    }

    private static void doThrow(ConstraintViolation<Object> violation) {
        String pn = violation.getPropertyPath().toString(), vm = violation.getMessage();
        throw new ValidateException(pn, vm, String.format("%s.%s %s", violation.getRootBeanClass().getSimpleName(), pn, vm));
    }

    public static <T> T validateConstructor(Constructor<?> member, Object[] parameterValues, Func<T> proceed) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        Set<ConstraintViolation<Object>> result = executableValidator.validateConstructorParameters(member, parameterValues);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }

        if (proceed == null) {
            return null;
        }
        T instance = proceed.get();
        result = executableValidator.validateConstructorReturnValue(member, instance);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }
        return instance;
    }

    public static void validateConstructor(Constructor<?> member, Object[] parameterValues, Object instance) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        Set<ConstraintViolation<Object>> result = executableValidator.validateConstructorParameters(member, parameterValues);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }

        if (instance == null) {
            return;
        }
        result = executableValidator.validateConstructorReturnValue(member, instance);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }
    }

    public static <T> T validateMethod(Method member, Object instance, Object[] parameterValues, Func<T> proceed) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, member, parameterValues)) {
            doThrow(violation);
        }
//        Method interfaceMethod = Reflects.getInterfaceMethod(member);
//        boolean hasIM = interfaceMethod != null;
//        if (hasIM) {
//            for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, interfaceMethod, parameterValues)) {
//                doThrow(violation);
//            }
//        }

        if (proceed == null) {
            return null;
        }
        T retVal;
        for (ConstraintViolation<Object> violation : executableValidator.validateReturnValue(instance, member, retVal = proceed.get())) {
            doThrow(violation);
        }
//        if (hasIM) {
//            for (ConstraintViolation<Object> violation : executableValidator.validateReturnValue(instance, interfaceMethod, retVal)) {
//                doThrow(violation);
//            }
//        }
        return retVal;
    }

    public static void validateMethod(Method member, Object instance, Object[] parameterValues, Object returnValue) {
        ExecutableValidator executableValidator = getValidator().forExecutables();
        for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, member, parameterValues)) {
            doThrow(violation);
        }
        for (ConstraintViolation<Object> violation : executableValidator.validateReturnValue(instance, member, returnValue)) {
            doThrow(violation);
        }
    }

//    void append(JSONObject j, Type type, Type[] typeArguments) {
//        ParameterizedTypeImpl pt = as(type, ParameterizedTypeImpl.class);
//        if (pt != null) {
//            Type[] ats = ifNull(typeArguments, pt.getActualTypeArguments());
//            int atsOffset = 0;
//            for (Field field : Reflects.getFieldMap(pt.getRawType()).values()) {
//                if (field.getName().equals("serialVersionUID")) {
//                    continue;
//                }
//                ParameterizedTypeImpl gpt = as(field.getGenericType(), ParameterizedTypeImpl.class);
//                if (gpt != null) {
//                    JSONObject n = new JSONObject();
//                    if (Iterable.class.isAssignableFrom(gpt.getRawType())) {
//                        JSONArray arr = new JSONArray();
//                        arr.add(n);
//                        j.put(field.getName(), arr);
//                    } else {
//                        j.put(field.getName(), n);
//                    }
//
//                    Type[] atas = gpt.getActualTypeArguments();
//                    if (atas[0] instanceof TypeVariableImpl) {
//                        append(n, ats[atsOffset], Arrays.subarray(atas, atsOffset, atsOffset += atas.length));
//                    } else {
//                        append(n, ats[atsOffset++], null);
//                    }
//                    continue;
//                }
//
//                j.put(field.getName(), getDesc(field));
//            }
//            return;
//        }
//
//        Class<?> clz = (Class<?>) type;
//        Type[] ats = typeArguments;
//        int atsOffset = 0;
//        for (Field field : Reflects.getFieldMap(clz).values()) {
//            if (field.getName().equals("serialVersionUID")) {
//                continue;
//            }
//            ParameterizedTypeImpl gpt = as(field.getGenericType(), ParameterizedTypeImpl.class);
//            if (gpt != null) {
//                JSONObject n = new JSONObject();
//                if (Iterable.class.isAssignableFrom(gpt.getRawType())) {
//                    JSONArray arr = new JSONArray();
//                    arr.add(n);
//                    j.put(field.getName(), arr);
//                } else {
//                    j.put(field.getName(), n);
//                }
//
//                Type[] atas = gpt.getActualTypeArguments();
//                if (atas[0] instanceof TypeVariableImpl && ats != null) {
//                    append(n, ats[atsOffset], Arrays.subarray(atas, atsOffset, atsOffset += atas.length));
//                } else {
//                    append(n, atas[0], null);
//                }
//                continue;
//            }
//
//            j.put(field.getName(), getDesc(field));
//        }
//    }
//
//    String getDesc(Field field) {
//        ApiModelProperty attr = field.getAnnotation(ApiModelProperty.class);
//        return field.getType().getSimpleName() + (attr == null ? "" : " " + attr.value());
//    }
}
