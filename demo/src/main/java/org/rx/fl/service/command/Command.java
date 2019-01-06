package org.rx.fl.service.command;

public interface Command {
    int ErrorStep = -1;

    default int getStep() {
        return 1;
    }

    default void setStep(int step) {
    }

    default HandleResult<String> errorResult(String userId, String message) {
        setStep(ErrorStep);
        return handleMessage(userId, message);
    }

    boolean peek(String message);

    HandleResult<String> handleMessage(String userId, String message);
}
