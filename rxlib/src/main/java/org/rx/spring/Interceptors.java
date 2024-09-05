package org.rx.spring;

import lombok.SneakyThrows;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.annotation.EnableLog;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.exception.ApplicationException;
import org.rx.net.http.HttpClient;
import org.rx.util.Servlets;
import org.rx.util.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.rx.core.Extends.as;
import static org.rx.core.Sys.logCtx;

//@within class level, @annotation method level
public class Interceptors {
    //@Aspect
    @Component
    @ControllerAdvice
    public static class ControllerInterceptor extends BaseInterceptor {
        final List<String> skipMethods = new CopyOnWriteArrayList<>(Arrays.toList("setServletRequest", "setServletResponse", "isSignIn"));

        public ControllerInterceptor() {
            logBuilder = new Sys.DefaultCallLogBuilder() {
                @Override
                protected Object shortArg(Class<?> declaringType, String methodName, Object arg) {
                    if (arg instanceof MultipartFile) {
                        return "[MultipartFile]";
                    }
                    return super.shortArg(declaringType, methodName, arg);
                }
            };
        }

        @SneakyThrows
        protected Object methodAround(ProceedingJoinPoint joinPoint, Tuple<HttpServletRequest, HttpServletResponse> httpEnv) {
            logCtx("url", httpEnv.left.getRequestURL().toString());
            return super.doAround(joinPoint);
        }

        @Override
        public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
            Tuple<HttpServletRequest, HttpServletResponse> httpEnv = httpEnv();
            if (httpEnv == null) {
                return super.doAround(joinPoint);
            }
            Signature signature = joinPoint.getSignature();
            MethodSignature ms = as(signature, MethodSignature.class);
            if (ms == null || skipMethods.contains(signature.getName())) {
                return joinPoint.proceed();
            }

            if (Strings.equals(httpEnv.left.getParameter("rmx"), Constants.ENABLE_FLAG)) {
                MxController controller = SpringContext.getBean(MxController.class, false);
                if (controller != null) {
                    return controller.health(httpEnv.left);
                }
            }
            Map<String, String> fts = RxConfig.INSTANCE.getMxHttpForwards().get(ms.getDeclaringType().getName());
            if (fts != null) {
                String fu = fts.get(ms.getName());
                if (fu != null) {
                    logCtx("fu", fu);
                    new HttpClient().forward(httpEnv.left, httpEnv.right, fu);
                    return null;
                }
            }
            IRequireSignIn requireSignIn = as(joinPoint.getTarget(), IRequireSignIn.class);
            if (requireSignIn != null && !requireSignIn.isSignIn(ms.getMethod(), joinPoint.getArgs())) {
                throw new NotSignInException();
            }
            return methodAround(joinPoint, httpEnv);
        }

        protected Tuple<HttpServletRequest, HttpServletResponse> httpEnv() {
            try {
                return Servlets.currentRequest();
            } catch (IllegalStateException e) {
                //ignore
            }
            return null;
        }

        @ExceptionHandler({Throwable.class})
        @ResponseStatus(HttpStatus.OK)
        @ResponseBody
        public Object onException(Throwable e) {
            String msg = null;
            if (e instanceof ConstraintViolationException) {
                ConstraintViolationException error = (ConstraintViolationException) e;
                msg = error.getConstraintViolations().stream().map(ConstraintViolation::getMessage).collect(Collectors.joining());
            } else if (e instanceof MethodArgumentNotValidException) {
                FieldError fieldError = ((MethodArgumentNotValidException) e).getBindingResult().getFieldError();
                if (fieldError != null) {
                    msg = String.format("Field '%s' %s", fieldError.getField(), fieldError.getDefaultMessage());
                }
            } else if (e instanceof BindException) {
                FieldError fieldError = ((BindException) e).getFieldError();
                if (fieldError != null) {
                    msg = String.format("Field '%s' %s", fieldError.getField(), fieldError.getDefaultMessage());
                }
            } else if (e instanceof HttpMessageNotReadableException) {
                msg = "Request body not readable";
            }
            if (msg == null) {
                msg = ApplicationException.getMessage(e);
            }

            if (SpringContext.controllerExceptionHandler != null) {
                return SpringContext.controllerExceptionHandler.apply(e, msg);
            }
            return new ResponseEntity<>(msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Aspect
    @Component
    public static class LogInterceptor extends BaseInterceptor {
        @Around("@annotation(org.rx.annotation.EnableLog) || @within(org.rx.annotation.EnableLog)")
        public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
            Signature signature = joinPoint.getSignature();
            if (signature instanceof ConstructorSignature) {
                ConstructorSignature cs = (ConstructorSignature) signature;
                if (doValidate(cs.getConstructor())) {
                    Validator.validateConstructor(cs.getConstructor(), joinPoint.getArgs(), joinPoint.getTarget());
                }
                return super.doAround(joinPoint);
            }

            MethodSignature ms = (MethodSignature) signature;
            if (doValidate(ms.getMethod())) {
                return Validator.validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), () -> super.doAround(joinPoint));
            }
            return super.doAround(joinPoint);
        }

        protected boolean doValidate(Executable r) {
            EnableLog a = r.getAnnotation(EnableLog.class);
            if (a == null) {
                a = r.getDeclaringClass().getAnnotation(EnableLog.class);
            }
            return a != null && a.doValidate();
        }
    }
}
