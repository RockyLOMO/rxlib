package org.springframework.service;

import io.netty.handler.codec.http.HttpHeaderValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.core.*;
import org.rx.exception.ApplicationException;
import org.rx.exception.TraceHandler;
import org.rx.net.http.HttpClient;
import org.rx.util.function.QuadraFunc;
import org.rx.util.function.TripleFunc;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.annotation.PostConstruct;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

import static org.rx.core.Extends.as;
import static org.rx.core.Sys.logCtx;

//WebMvcConfigurationSupport
@Slf4j
@RequiredArgsConstructor
@Configuration
public class RWebConfig implements WebMvcConfigurer {
    public static void enableTrace(String traceName) {
        if (traceName == null) {
            traceName = Constants.DEFAULT_TRACE_NAME;
        }
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        conf.setTraceName(traceName);
        ThreadPool.onTraceIdChanged.first((s, e) -> logCtx(conf.getTraceName(), e));
    }

    public static void beginTrace(HttpServletRequest request, HttpServletResponse response) {
        RxConfig.ThreadPoolConfig conf = RxConfig.INSTANCE.getThreadPool();
        String traceName = conf.getTraceName();
        if (Strings.isEmpty(traceName)) {
            return;
        }

        String parentTraceId = request.getHeader(traceName);
        String traceId = ThreadPool.startTrace(parentTraceId);
        response.setHeader(traceName, traceId);
    }

    public static void endTrace(HttpServletRequest request, HttpServletResponse response) {
        ThreadPool.endTrace();
    }

    final HandlerImpl handler;
    final FilterImpl filter;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(handler)
                .addPathPatterns("/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    public FilterRegistrationBean<FilterImpl> filter() {
        FilterRegistrationBean<FilterImpl> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @ControllerAdvice
//    @Component
    public static class ExceptionAdvice {
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

            TripleFunc<Throwable, String, Object> exceptionHandle = SpringContext.exceptionHandle;
            if (exceptionHandle != null) {
                return exceptionHandle.apply(e, msg);
            }
            log.error("HttpException", e);
            return new ResponseEntity<>(msg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    //@within class level, @annotation method level
    @Aspect
    @RequiredArgsConstructor
    @Component
    public static class HandlerAspect extends BaseInterceptor {
        final HandlerUtil util;

        @PostConstruct
        public void init() {
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

        @Around("within(@org.springframework.web.bind.annotation.RestController *) && execution(public * *(..))")
        @Override
        public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
            ServletRequestAttributes ra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            Signature signature = joinPoint.getSignature();
            MethodSignature ms = as(signature, MethodSignature.class);
            if (ra == null || ms == null) {
                return joinPoint.proceed();
            }
            if (!util.around(ra.getRequest(), ra.getResponse())) {
                return null;
            }

            beginTrace(ra.getRequest(), ra.getResponse());
            TripleFunc<HttpServletRequest, HttpServletResponse, Object> preHandle = SpringContext.preHandle;
            if (preHandle != null) {
                Object r = preHandle.apply(ra.getRequest(), ra.getResponse());
                if (r != null) {
                    return r;
                }
            }
            try {
                IRequireSignIn requireSignIn = as(joinPoint.getTarget(), IRequireSignIn.class);
                if (requireSignIn != null && !requireSignIn.isSignIn(ms.getMethod(), joinPoint.getArgs())) {
                    throw new NotSignInException();
                }

                RxConfig.RestConfig conf = RxConfig.INSTANCE.getRest();

                String declaringTypeName = ms.getDeclaringType().getName();
                Map<String, String> fts = conf.getForwards().get(declaringTypeName);
                if (fts != null) {
                    String fu = fts.get(ms.getName());
                    if (fu != null) {
                        new HttpClient().forward(ra.getRequest(), ra.getResponse(), fu);
                        return null;
                    }
                }

                int logMode = conf.getLogMode();
                if (logMode == 0
                        || conf.getLogNameMatcher().matches(declaringTypeName)) {
                    return joinPoint.proceed();
                }

                logCtx("url", ra.getRequest().getRequestURL().toString());
                Object result = super.around(joinPoint);
                QuadraFunc<HttpServletRequest, HttpServletResponse, Object, Object> postHandle = SpringContext.postHandle;
                if (postHandle != null) {
                    Object r = postHandle.apply(ra.getRequest(), ra.getResponse(), result);
                    if (r != null) {
                        return r;
                    }
                }
                return result;
            } finally {
                endTrace(ra.getRequest(), ra.getResponse());
            }
        }
    }

    @RequiredArgsConstructor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Component
    public static class HandlerImpl implements HandlerInterceptor {
        final HandlerUtil util;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (!util.around(request, response)) {
                return false;
            }

//            beginTrace(request, response);
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
//            endTrace(request, response);
        }
    }

    @RequiredArgsConstructor
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Component
    @WebFilter(urlPatterns = "/*")
    public static class FilterImpl implements Filter {
        final HandlerUtil util;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest servletRequest = (HttpServletRequest) request;
            HttpServletResponse servletResponse = (HttpServletResponse) response;
            if (Strings.startsWithIgnoreCase(request.getContentType(), HttpHeaderValues.APPLICATION_JSON)
                    && !(request instanceof ContentCachingRequestWrapper)) {
                request = new ContentCachingRequestWrapper(servletRequest);
                log.debug("RWebConfigurer rebuild ContentCachingRequest");
            }

            if (util.around(servletRequest, servletResponse)) {
                chain.doFilter(request, response);
            }
        }
    }
}
