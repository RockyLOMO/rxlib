package org.rx.spring;

import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.core.*;
import org.rx.util.Servlets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.rx.core.Contract.as;

@ControllerAdvice
@Aspect
@Component
public class ControllerInterceptor extends LogInterceptor {
    private final List<String> skipMethods = new CopyOnWriteArrayList<>(Arrays.toList("setServletRequest", "setServletResponse", "isSignIn"));

    public ControllerInterceptor() {
        super.argShortSelector = (s, p) -> {
            if (p instanceof MultipartFile) {
                return "[MultipartFile]";
            }
            return p;
        };
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
        SpringContext.metrics("url", Servlets.currentRequest().left.getRequestURL().toString());
        return super.doAround(joinPoint);
    }

    @SneakyThrows
    @ExceptionHandler({Exception.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public Object onException(Exception e, HttpServletRequest request) {
        String msg = App.log(request.getRequestURL().toString(), e);
        if (SpringContext.exceptionReturnHandler == null) {
            return msg;
        }
        return SpringContext.exceptionReturnHandler.invoke(msg);
    }
}
