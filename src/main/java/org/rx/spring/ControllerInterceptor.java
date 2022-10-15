package org.rx.spring;

import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.exception.ApplicationException;
import org.rx.exception.TraceHandler;
import org.rx.util.Servlets;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.rx.core.Extends.as;
import static org.rx.core.Extends.quietly;

//@Aspect
@Component
@ControllerAdvice
public class ControllerInterceptor extends BaseInterceptor {
    final List<String> skipMethods = new CopyOnWriteArrayList<>(Arrays.toList("setServletRequest", "setServletResponse", "isSignIn"));

    public ControllerInterceptor() {
        super.enableTrace();
    }

    @Override
    protected Object shortArg(Signature signature, Object arg) {
        if (arg instanceof MultipartFile) {
            return "[MultipartFile]";
        }
        return super.shortArg(signature, arg);
    }

    @Override
    protected String startTrace(String parentTraceId) {
        Tuple<HttpServletRequest, HttpServletResponse> httpEnv = quietly(Servlets::currentRequest);
        if (httpEnv != null) {
            String tn = RxConfig.INSTANCE.getThreadPool().getTraceName();
            if (parentTraceId == null) {
                parentTraceId = httpEnv.left.getHeader(tn);
            }
            String tid = super.startTrace(parentTraceId);
            httpEnv.right.setHeader(tn, tid);
            return tid;
        }
        return super.startTrace(parentTraceId);
    }

    @Override
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = as(signature, MethodSignature.class);
        if (methodSignature == null || skipMethods.contains(signature.getName())) {
            return joinPoint.proceed();
        }
        IRequireSignIn requireSignIn = as(joinPoint.getTarget(), IRequireSignIn.class);
        if (requireSignIn != null && !requireSignIn.isSignIn(methodSignature.getMethod(), joinPoint.getArgs())) {
            throw new NotSignInException();
        }
        App.logCtx("url", Servlets.currentRequest().left.getRequestURL().toString());
        return super.doAround(joinPoint);
    }

    @SneakyThrows
    @ExceptionHandler({Exception.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public Object onException(Exception e, HttpServletRequest request) {
        TraceHandler.INSTANCE.log(request.getRequestURL().toString(), e);
        String msg = null;
        if (e instanceof MethodArgumentNotValidException) {
            FieldError fieldError = ((MethodArgumentNotValidException) e).getBindingResult().getFieldError();
            if (fieldError != null) {
                msg = String.format("Field '%s' %s", fieldError.getField(), fieldError.getDefaultMessage());
            }
        }
        if (msg == null) {
            msg = ApplicationException.getMessage(e);
        }

        if (SpringContext.controllerExceptionHandler != null) {
            return SpringContext.controllerExceptionHandler.invoke(e, msg);
        }
        return new ResponseEntity<>(msg, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
