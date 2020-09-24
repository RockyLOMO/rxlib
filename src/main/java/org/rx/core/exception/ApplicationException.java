package org.rx.core.exception;

import lombok.Getter;
import lombok.Setter;
import org.rx.core.*;

import java.util.UUID;

import static org.rx.core.Contract.*;

/**
 * 根据Exception来读取errorCode.yml的错误信息
 * ex.fillInStackTrace()
 * https://northconcepts.com/blog/2013/01/18/6-tips-to-improve-your-exception-handling/
 */
@Getter
public class ApplicationException extends InvalidException {
    static {
        Container.getInstance().register(ExceptionCodeHandler.class, new DefaultExceptionCodeHandler());
    }

    public static final String DEFAULT_MESSAGE = "网络繁忙，请稍后再试。";
    private final UUID id = UUID.randomUUID();
    private final NQuery<StackTraceElement> stacks;
    private String methodCode;
    private Enum enumCode;
    private final Object[] codeValues;
    @Setter
    private String friendlyMessage;

    @Override
    public String getMessage() {
        return isNull(friendlyMessage, String.format("%s %s", id, super.getMessage()));
    }

    public String getFriendlyMessage() {
        return isNull(friendlyMessage, DEFAULT_MESSAGE);
    }

    public ApplicationException(Object[] codeValues) {
        this((String) null, null, codeValues);
    }

    public ApplicationException(Object[] codeValues, String methodCode) {
        this(methodCode, null, codeValues);
    }

    public ApplicationException(Object[] codeValues, Throwable cause) {
        this((String) null, cause, codeValues);
    }

    public ApplicationException(String methodCode, Throwable cause, Object[] codeValues) {
        super(cause != null ? cause.getMessage() : null, cause);
        this.methodCode = methodCode;
        if (codeValues == null) {
            codeValues = Arrays.EMPTY_OBJECT_ARRAY;
        }
        this.codeValues = codeValues;
        stacks = Reflects.threadStack(8);
        init();
    }

    public ApplicationException(Enum enumCode, Throwable cause, Object[] codeValues) {
        super(cause != null ? cause.getMessage() : null, cause);
        this.enumCode = enumCode;
        if (codeValues == null) {
            codeValues = Arrays.EMPTY_OBJECT_ARRAY;
        }
        this.codeValues = codeValues;
        stacks = Reflects.threadStack(8);
        init();
    }

    private void init() {
        ExceptionCodeHandler handler = Container.getInstance().get(ExceptionCodeHandler.class);
        handler.handle(this);
    }
}
