package org.rx.exception;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.rx.core.*;

import java.util.UUID;

import static org.rx.core.App.*;

/**
 * 根据Exception来读取errorCode.yml的错误信息
 * ex.fillInStackTrace()
 * https://northconcepts.com/blog/2013/01/18/6-tips-to-improve-your-exception-handling/
 */
@Getter
public class ApplicationException extends InvalidException {
    public static final String DEFAULT_MESSAGE = "网络繁忙，请稍后再试。";

    public static String getMessage(Throwable e) {
        if (e == null) {
            return DEFAULT_MESSAGE;
        }

        ApplicationException applicationException = as(e, ApplicationException.class);
        if (applicationException == null) {
            return isNull(e.getMessage(), DEFAULT_MESSAGE);
        }
        return applicationException.getFriendlyMessage();
    }

    private final UUID id = UUID.randomUUID();
    private final Object errorCode;
    private final Object[] codeValues;
    @Setter
    private String friendlyMessage;
    private final NQuery<StackTraceElement> stacks;

    @Override
    public String getMessage() {
        return isNull(friendlyMessage, String.format("%s %s", id, super.getMessage()));
    }

    public String getFriendlyMessage() {
        return isNull(friendlyMessage, DEFAULT_MESSAGE);
    }

    public ApplicationException(Object[] codeValues) {
        this(codeValues, null);
    }

    public ApplicationException(Object[] codeValues, Throwable cause) {
        this(Strings.EMPTY, codeValues, cause);
    }

    public <T extends Enum<T>> ApplicationException(T errorCode) {
        this(errorCode, null, null);
    }

    public <T extends Enum<T>> ApplicationException(T errorCode, Throwable cause) {
        this(errorCode, null, cause);
    }

    public <T extends Enum<T>> ApplicationException(T errorCode, Object[] codeValues) {
        this(errorCode, codeValues, null);
    }

    public <T extends Enum<T>> ApplicationException(T errorCode, Object[] codeValues, Throwable cause) {
        this((Object) errorCode, codeValues, cause);
    }

    public ApplicationException(String errorCode, Object[] codeValues) {
        this(errorCode, codeValues, null);
    }

    public ApplicationException(String errorCode, Object[] codeValues, Throwable cause) {
        this((Object) errorCode, codeValues, cause);
    }

    protected ApplicationException(@NonNull Object errorCode, Object[] codeValues, Throwable cause) {
        super(cause != null ? cause.getMessage() : null, cause);
        require(errorCode, NQuery.of(Enum.class, String.class).any(p -> Reflects.isInstance(errorCode, p)));

        this.errorCode = errorCode;
        if (codeValues == null) {
            codeValues = Arrays.EMPTY_OBJECT_ARRAY;
        }
        this.codeValues = codeValues;
        if (errorCode instanceof CharSequence) {
            stacks = Reflects.stackTrace(8);
        } else {
            stacks = null;
        }
        Container.get(ExceptionCodeHandler.class).handle(this);
    }
}
