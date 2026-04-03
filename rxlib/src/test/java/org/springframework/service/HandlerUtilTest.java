package org.springframework.service;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.rx.exception.InvalidException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
public class HandlerUtilTest {

    private HandlerUtil handlerUtil;

    @BeforeEach
    void setUp() {
        handlerUtil = new HandlerUtil();
    }

    @Test
    void testInvokeEx_InvalidExpression() {
        // Test with invalid expression (no dot)
        InvalidException exception = assertThrows(InvalidException.class, () -> {
            handlerUtil.invokeEx("invalidExpression", Collections.emptyList());
        });
        assertEquals("Class name not fund", exception.getMessage());
    }

    @Test
    void testInvokeEx_ClassNotFound() {
        // Test with non-existent class
        assertThrows(ClassNotFoundException.class, () -> {
            handlerUtil.invokeEx("com.nonexistent.Class.method", Collections.emptyList());
        });
    }

    @Test
    void testInvokeEx_InstanceMethodWithObject() throws Exception {
        // Test invoking method on a specific object instance via Spring context
        TestService testService = new TestService();
        try (MockedStatic<SpringContext> mockedSpringContext = mockStatic(SpringContext.class)) {
            mockedSpringContext.when(() -> SpringContext.getBean(eq(TestService.class), eq(false)))
                    .thenReturn(testService);
            
            List<Object> args = Arrays.asList("test");
            Object result = handlerUtil.invokeEx("org.springframework.service.HandlerUtilTest$TestService.process", args);
            assertEquals("processed: test", result);
        }
    }

    @Test
    void testInvokeEx_NestedMethodInvocation() throws Exception {
        // Test invoking method on nested object
        TestService testService = new TestService();
        try (MockedStatic<SpringContext> mockedSpringContext = mockStatic(SpringContext.class)) {
            mockedSpringContext.when(() -> SpringContext.getBean(eq(TestService.class), eq(false)))
                    .thenReturn(testService);
            
            List<Object> args = Arrays.asList("arg");
            Object result = handlerUtil.invokeEx("org.springframework.service.HandlerUtilTest$TestService.nestedService.process", args);
            assertEquals("nestedService processed: arg", result);
        }
    }

    @Test
    void testInvokeEx_MixedArgumentTypes() throws Exception {
        // Test with mixed argument types
        TestService testService = new TestService();
        try (MockedStatic<SpringContext> mockedSpringContext = mockStatic(SpringContext.class)) {
            mockedSpringContext.when(() -> SpringContext.getBean(eq(TestService.class), eq(false)))
                    .thenReturn(testService);
            
            List<Object> args = Arrays.asList("Hello", 123, true);
            Object result = handlerUtil.invokeEx("org.springframework.service.HandlerUtilTest$TestService.mixedArgs", args);
            assertEquals("Hello-123-true", result);
        }
    }

    @Test
    void testInvokeEx_WithJsonConversion() throws Exception {
        // Test parameter conversion from JSON
        TestService testService = new TestService();
        try (MockedStatic<SpringContext> mockedSpringContext = mockStatic(SpringContext.class)) {
            mockedSpringContext.when(() -> SpringContext.getBean(eq(TestService.class), eq(false)))
                    .thenReturn(testService);
            
            List<Object> args = Arrays.asList("{\"key\":\"value\"}");
            Object result = handlerUtil.invokeEx("org.springframework.service.HandlerUtilTest$TestService.fromJson", args);
            assertEquals("value", result);
        }
    }

    // Test helper classes
    public static class TestService {
        public final NestedService nestedService = new NestedService();
        
        public String process(String input) {
            return "processed: " + input;
        }
        
        public String fromJson(String json) {
            // Simple JSON parsing for test
            return json.split("\"")[3];
        }
        
        public String mixedArgs(String str, Integer num, Boolean flag) {
            return str + "-" + num + "-" + flag;
        }
    }

    public static class NestedService {
        public String value = "nested-nested";
        
        public String process(String input) {
            return "nestedService processed: " + input;
        }
    }
}
