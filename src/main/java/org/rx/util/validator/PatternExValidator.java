package org.rx.util.validator;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

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
