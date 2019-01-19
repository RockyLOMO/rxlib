package org.rx.fl.service.command;

public interface Command {
    default int getStep() {
        return 1;
    }

    default void setStep(int step) {
    }

    boolean peek(String message);

    HandleResult<String> handleMessage(String userId, String message);

    default HandleResult<String> previousMessage(String userId, String message) {
        setStep(getStep() - 1);
        return handleMessage(userId, message);
    }
}
