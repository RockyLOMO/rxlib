package org.rx.spring;

import lombok.SneakyThrows;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.annotation.EnableTrace;
import org.rx.bean.ProceedEventArgs;
import org.rx.bean.Tuple;
import org.rx.core.Arrays;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.exception.ApplicationException;
import org.rx.exception.TraceHandler;
import org.rx.net.http.HttpClient;
import org.rx.util.Servlets;
import org.rx.util.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.rx.core.Extends.as;
import static org.rx.core.Sys.logCtx;

//@within class level, @annotation method level
public class Interceptors {
    //@Aspect
    @Component
    @ControllerAdvice
    public static class ControllerInterceptor extends BaseInterceptor {
        final List<String> skipMethods = new CopyOnWriteArrayList<>(Arrays.toList("setServletRequest", "setServletResponse", "isSignIn"));

        @SneakyThrows
        protected Object methodAround(ProceedingJoinPoint joinPoint, Tuple<HttpServletRequest, HttpServletResponse> httpEnv) {
            logCtx("url", httpEnv.left.getRequestURL().toString());
            return super.doAround(joinPoint);
        }

        @Override
        protected Object shortArg(Signature signature, Object arg) {
            if (arg instanceof MultipartFile) {
                return "[MultipartFile]";
            }
            return super.shortArg(signature, arg);
        }

        @Override
        protected String startTrace(JoinPoint joinPoint, String parentTraceId) {
            Tuple<HttpServletRequest, HttpServletResponse> httpEnv = httpEnv();
            if (httpEnv == null) {
                return super.startTrace(joinPoint, parentTraceId);
            }

            String tn = RxConfig.INSTANCE.getThreadPool().getTraceName();
            if (parentTraceId == null) {
                parentTraceId = httpEnv.left.getHeader(tn);
            }
            String tid = super.startTrace(joinPoint, parentTraceId);
            httpEnv.right.setHeader(tn, tid);
            return tid;
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
                MxController controller = SpringContext.getBean(MxController.class);
                if (controller != null) {
                    return controller.health(httpEnv.left);
                }
            }
            Map<String, String> fts = RxConfig.INSTANCE.getHttpForwards().get(ms.getDeclaringType());
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
        public Object onException(Throwable e, HttpServletRequest request) {
            if (!Boolean.TRUE.equals(request.getAttribute("_skipGlobalLog"))) {
                TraceHandler.INSTANCE.log(request.getRequestURL().toString(), e);
            }
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
                return SpringContext.controllerExceptionHandler.apply(e, msg);
            }
            return new ResponseEntity<>(msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Aspect
    @Component
    public static class TraceInterceptor extends BaseInterceptor {
        public void setTraceName(String traceName) {
            super.enableTrace(traceName);
        }

        @Around("@annotation(org.rx.annotation.EnableTrace) || @within(org.rx.annotation.EnableTrace)")
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

        @Override
        protected void onLog(Signature signature, ProceedEventArgs eventArgs, String paramSnapshot) {
            Executable r = signature instanceof ConstructorSignature
                    ? ((ConstructorSignature) signature).getConstructor()
                    : ((MethodSignature) signature).getMethod();
            EnableTrace a = r.getAnnotation(EnableTrace.class);
            if (a == null || a.doLog()) {
                super.onLog(signature, eventArgs, paramSnapshot);
            }
        }

        protected boolean doValidate(Executable r) {
            EnableTrace a = r.getAnnotation(EnableTrace.class);
            if (a == null) {
                a = r.getDeclaringClass().getAnnotation(EnableTrace.class);
            }
            return a != null && a.doValidate();
        }
    }
}
