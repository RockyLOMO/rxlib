package org.rx.spring;

import com.alibaba.fastjson.JSONArray;
import org.rx.core.Reflects;
import org.rx.core.Strings;
import org.rx.net.AuthenticEndpoint;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.io.File;

import static org.rx.core.App.toJsonArray;

//DataSize 和 Duration
public class AppConfigConverters {
    @Component
    @ConfigurationPropertiesBinding
    public static class JSONArrayConverter implements Converter<String, JSONArray> {
        @Override
        public JSONArray convert(String s) {
            if (Strings.isBlank(s)) {
                return new JSONArray();
            }
            return toJsonArray(s);
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class AuthenticEndpointConverter implements Converter<String, AuthenticEndpoint> {
        @Override
        public AuthenticEndpoint convert(String s) {
            return new AuthenticEndpoint(s);
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class FileConverter implements Converter<String, File> {
        @Override
        public File convert(String s) {
            return new File(s);
        }
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class ClassConverter implements Converter<String, Class<?>> {
        @Override
        public Class<?> convert(String s) {
            return Reflects.loadClass(s, false);
        }
    }
}
