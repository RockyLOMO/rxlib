package org.rx.util.validator;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import org.rx.util.LogInterceptor;
import org.rx.util.StringBuilder;
import org.rx.util.function.ThrowableFunc;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * http://www.cnblogs.com/pixy/p/5306567.html
 */
public class ValidateUtil extends LogInterceptor {
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
                                        boolean validateValues, ThrowableFunc returnValueFuc)
            throws Throwable {
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
                retVal = returnValueFuc.invoke(null))) {
            doThrow(violation);
        }
        return retVal;
    }

    /**
     * Annotation expression只对method有效
     * 
     * @param joinPoint
     * @param msg
     * @return
     * @throws Throwable
     */
    @Override
    protected Object onProcess(ProceedingJoinPoint joinPoint, StringBuilder msg) throws Throwable {
        Class targetType = joinPoint.getTarget().getClass();
        Signature signature = joinPoint.getSignature();
        Executable member;
        if (signature instanceof ConstructorSignature) {
            member = ((ConstructorSignature) signature).getConstructor();
        } else {
            member = ((MethodSignature) signature).getMethod();
        }

        msg.setPrefix(String.format("[Valid] %s.%s ", targetType.getSimpleName(), signature.getName()));
        EnableValid attr = member.getAnnotation(EnableValid.class);
        if (attr == null) {
            attr = (EnableValid) targetType.getAnnotation(EnableValid.class);
            if (attr == null) {
                msg.appendLine("skip validate..");
                return joinPoint.proceed();
            }
        }

        int flags = attr.value();
        boolean validateValues = hasFlags(flags, EnableValid.ParameterValues);
        if (hasFlags(flags, EnableValid.Method)) {
            if (signature instanceof ConstructorSignature) {
                ConstructorSignature cs = (ConstructorSignature) signature;
                validateConstructor(cs.getConstructor(), joinPoint.getArgs(), validateValues);
                return super.onProcess(joinPoint, msg);
            }

            MethodSignature ms = (MethodSignature) signature;
            return validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), validateValues,
                    p -> super.onProcess(joinPoint, msg));
        }

        if (validateValues) {
            for (Object parameterValue : joinPoint.getArgs()) {
                validateBean(parameterValue);
            }
        }

        msg.appendLine("validate ok..").setPrefix(null);
        return super.onProcess(joinPoint, msg);
    }

    @Override
    protected Object onException(Exception ex, StringBuilder msg) throws Throwable {
        msg.appendLine("validate fail %s..", ex.getMessage());
        throw ex;
    }

    //#region Nested
    public interface RegularExp {
        /**
         * 验证email地址
         */
        String Email                      = "^[\\w-]+(\\.[\\w-]+)*@[\\w-]+(\\.[\\w-]+)+$";
        /**
         * 验证手机号码
         */
        String Mobile                     = "^0{0,1}1[3|5|7|8]\\d{9}$";
        /**
         * 验证电话号码
         */
        String Telephone                  = "(\\d+-)?(\\d{4}-?\\d{7}|\\d{3}-?\\d{8}|^\\d{7,8})(-\\d+)?";
        /**
         * 验证日期（YYYY-MM-DD）
         */
        String Date                       = "^((((1[6-9]|[2-9]\\d)\\d{2})-(0?[13578]|1[02])-(0?[1-9]|[12]\\d|3[01]))|(((1[6-9]|[2-9]\\d)\\d{2})-(0?[13456789]|1[012])-(0?[1-9]|[12]\\d|30))|(((1[6-9]|[2-9]\\d)\\d{2})-0?2-(0?[1-9]|1\\d|2[0-8]))|(((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00))-0?2-29-))$";
        /**
         * 验证日期和时间（YYYY-MM-DD HH:MM:SS）
         */
        String DateTime                   = "^((((1[6-9]|[2-9]\\d)\\d{2})-(0?[13578]|1[02])-(0?[1-9]|[12]\\d|3[01]))|(((1[6-9]|[2-9]\\d)\\d{2})-(0?[13456789]|1[012])-(0?[1-9]|[12]\\d|30))|(((1[6-9]|[2-9]\\d)\\d{2})-0?2-(0?[1-9]|1\\d|2[0-8]))|(((1[6-9]|[2-9]\\d)(0[48]|[2468][048]|[13579][26])|((16|[2468][048]|[3579][26])00))-0?2-29-)) (20|21|22|23|[0-1]?\\d):[0-5]?\\d:[0-5]?\\d$";
        /**
         * 验证IP
         */
        String IP                         = "^(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])\\.(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])\\.(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])\\.(\\d{1,2}|1\\d\\d|2[0-4]\\d|25[0-5])$";
        /**
         * 验证URL
         */
        String Url                        = "^http(s)?://([\\w-]+\\.)+[\\w-]+(/[\\w- ./?%&=]*)?$";
        /**
         * 验证浮点数
         */
        String Float                      = "^(-?\\d+)(\\.\\d+)?$";
        /**
         * 验证整数
         */
        String Integer                    = "^-?\\d+$";
        /**
         * 验证正浮点数
         */
        String PlusFloat                  = "^(([0-9]+\\.[0-9]*[1-9][0-9]*)|([0-9]*[1-9][0-9]*\\.[0-9]+)|([0-9]*[1-9][0-9]*))$";
        /**
         * 验证正整数
         */
        String PlusInteger                = "^[0-9]*[1-9][0-9]*$";
        /**
         * 验证负浮点数
         */
        String MinusFloat                 = "^(-(([0-9]+\\.[0-9]*[1-9][0-9]*)|([0-9]*[1-9][0-9]*\\.[0-9]+)|([0-9]*[1-9][0-9]*)))$";
        /**
         * 验证负整数
         */
        String MinusInteger               = "^-[0-9]*[1-9][0-9]*$";
        /**
         * 验证非负浮点数（正浮点数 + 0）
         */
        String UnMinusFloat               = "^\\d+(\\.\\d+)?$";
        /**
         * 验证非负整数（正整数 + 0）
         */
        String UnMinusInteger             = "^\\d+$";
        /**
         * 验证非正浮点数（负浮点数 + 0）
         */
        String UnPlusFloat                = "^((-\\d+(\\.\\d+)?)|(0+(\\.0+)?))$";
        /**
         * 验证非正整数（负整数 + 0）
         */
        String UnPlusInteger              = "^((-\\d+)|(0+))$";
        /**
         * 验证由数字组成的字符串
         */
        String Numeric                    = "^[0-9]+$";
        /**
         * 验证由数字和26个英文字母组成的字符串
         */
        String NumericOrLetter            = "^[A-Za-z0-9]+$";
        /**
         * 验证由数字、26个英文字母或者下划线组成的字符串
         */
        String NumericOrLetterOrUnderline = "^\\w+$";
        /**
         * 验证由数字和26个英文字母或中文组成的字符串
         */
        String NumbericOrLetterOrChinese  = "^[A-Za-z0-9\\u4E00-\\u9FA5\\uF900-\\uFA2D]+$";
        /**
         * 验证由26个英文字母组成的字符串
         */
        String Letter                     = "^[A-Za-z]+$";
        /**
         * 验证由26个英文字母的小写组成的字符串
         */
        String LowerLetter                = "^[a-z]+$";
        /**
         * 验证由26个英文字母的大写组成的字符串
         */
        String UpperLetter                = "^[A-Z]+$";
        /**
         * 验证由中文组成的字符串
         */
        String Chinese                    = "^[\\u4E00-\\u9FA5\\uF900-\\uFA2D]+$";
        /**
         * 检测是否符合邮编格式
         */
        String PostCode                   = "^\\d{6}$";
        /**
         * 验证颜色（#ff0000）
         */
        String Color                      = "^#[a-fA-F0-9]{6}";
        /**
         * 通过文件扩展名验证图像格式
         */
        String ImageFormat                = "\\.(?i:jpg|bmp|gif|ico|pcx|jpeg|tif|png|raw|tga)$";
    }
    //#endregion

    public static boolean isMatch(String input, String regularExp) {
        return input == null || regularExp == null ? false : Pattern.matches(input, regularExp);
    }
}
