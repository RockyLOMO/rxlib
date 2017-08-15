package org.rx.validator;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * Created by IntelliJ IDEA. User: za-wangxiaoming Date: 2017/8/1
 */
public class PatternExValidator implements ConstraintValidator<PatternEx, String> {
    private PatternEx patternEx;

    @Override
    public void initialize(PatternEx patternEx) {
        this.patternEx = patternEx;
    }

    @Override
    public boolean isValid(String s, ConstraintValidatorContext constraintValidatorContext) {
        if (s == null) {
            return true;
        }

        Pattern p = Pattern.compile(patternEx.value().getRegexp(), Pattern.CASE_INSENSITIVE);
        return p.matcher(s).matches();
    }
}
