package org.rx.lr.web;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.rx.SystemException;
import org.rx.util.validator.ConstraintException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletRequest;

import java.io.Serializable;

import static org.rx.SystemException.DefaultMessage;

@Slf4j
@Order(0)
@ControllerAdvice
public class ControllerExceptionHandler {
    @Data
    private class ErrorResult implements Serializable {
        private int errorCode;
        private String errorMsg;
    }

    @ResponseStatus(HttpStatus.OK)
    @ExceptionHandler({Exception.class})
    public ResponseEntity bizException(Exception e, HttpServletRequest request) {
        String msg = DefaultMessage;
        Exception logEx = e;
        if (e instanceof ConstraintException) {
            //参数校验错误 ignore log
            msg = e.getMessage();
            logEx = null;
        } else if (e instanceof SystemException) {
//            $<InvalidOperationException> out = $();
//            if (((SystemException) e).tryGet(out, InvalidOperationException.class)) {
//                errorMsg = out.$.getFriendlyMessage();
//                logEx = out.$;
//            }
            msg = ((SystemException) e).getFriendlyMessage();
        }
        if (logEx != null) {
            log.error("异常Trace", logEx);
        }

        ErrorResult r = new ErrorResult();
        r.setErrorCode(-1);
        r.setErrorMsg(msg);
        return ResponseEntity.ok(r);
    }
}
