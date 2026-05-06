package org.rx.util;

import org.junit.jupiter.api.Test;
import org.rx.annotation.Mapping;
import org.rx.bean.DateTime;
import org.rx.bean.FlagsEnum;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BeanMapperAssignmentTest {
    public interface TrimFormatMapper {
        TrimFormatMapper INSTANCE = BeanMapper.DEFAULT.define(TrimFormatMapper.class);

        default FlagsEnum<BeanMapFlag> getFlags() {
            return BeanMapFlag.NONE.flags();
        }

        @Mapping(source = "name", target = "info", trim = true, format = "a%sb")
        TrimFormatTarget map(TrimFormatSource source);
    }

    public interface SkipNullMapper {
        SkipNullMapper INSTANCE = BeanMapper.DEFAULT.define(SkipNullMapper.class);

        default FlagsEnum<BeanMapFlag> getFlags() {
            return BeanMapFlag.SKIP_NULL.flags();
        }

        @Mapping(source = "name", target = "info")
        SkipNullTarget map(SkipNullSource source);
    }

    public interface IgnoreMapper {
        IgnoreMapper INSTANCE = BeanMapper.DEFAULT.define(IgnoreMapper.class);

        default FlagsEnum<BeanMapFlag> getFlags() {
            return BeanMapFlag.NONE.flags();
        }

        @Mapping(source = "index2", target = "index", nullValueStrategy = BeanMapNullValueStrategy.Ignore)
        SameNameTarget map(SameNameSource source, SameNameTarget target);
    }

    public interface ConverterMapper {
        ConverterMapper INSTANCE = BeanMapper.DEFAULT.define(ConverterMapper.class);

        default FlagsEnum<BeanMapFlag> getFlags() {
            return BeanMapFlag.NONE.flags();
        }

        @Mapping(source = "name", target = "converted", converter = UpperCaseConverter.class)
        ConverterTarget map(ConverterSource source);
    }

    public static class UpperCaseConverter implements BeanMapConverter<String, String> {
        @Override
        public String convert(String sourceValue, Class<String> targetType, String propertyName) {
            return sourceValue == null ? null : sourceValue.toUpperCase(Locale.ROOT);
        }
    }

    @Test
    public void explicitSourceValueIsTrimmedAndFormatted() {
        TrimFormatSource source = new TrimFormatSource();
        source.setName("  Neo  ");

        TrimFormatTarget target = TrimFormatMapper.INSTANCE.map(source);

        assertEquals("aNeob", target.getInfo());
    }

    @Test
    public void skipNullUsesExplicitSourceBeforeNullDecision() {
        SkipNullSource source = new SkipNullSource();
        source.setName("Alice");

        SkipNullTarget target = SkipNullMapper.INSTANCE.map(source);

        assertEquals("Alice", target.getInfo());
    }

    @Test
    public void ignoreNullStrategyUsesExplicitSourceBeforeNullDecision() {
        SameNameSource source = new SameNameSource();
        source.setIndex(null);
        source.setIndex2(7);
        SameNameTarget target = new SameNameTarget();
        target.setIndex(99);

        SameNameTarget result = IgnoreMapper.INSTANCE.map(source, target);

        assertEquals(7, result.getIndex().intValue());
    }

    @Test
    public void converterReceivesExplicitSourceValue() {
        ConverterSource source = new ConverterSource();
        source.setName("alice");

        ConverterTarget target = ConverterMapper.INSTANCE.map(source);

        assertEquals("ALICE", target.getConverted());
    }

    @Test
    public void incompatibleImplicitPropertyIsSkippedWhenOnlyLoggingMissedMappings() {
        IncompatibleSource source = new IncompatibleSource();
        source.setBirth(DateTime.valueOf("2020-02-20 00:00:00"));
        IncompatibleTarget target = new IncompatibleTarget();
        target.setBirth(42);

        BeanMapper.DEFAULT.map(source, target, BeanMapFlag.LOG_ON_MISS_MAPPING.flags());

        assertEquals(42, target.getBirth());
    }

    @Test
    public void incompatibleImplicitPropertyThrowsWhenThrowOnMissMappingEnabled() {
        IncompatibleSource source = new IncompatibleSource();
        source.setBirth(DateTime.valueOf("2020-02-20 00:00:00"));

        assertThrows(RuntimeException.class, () -> BeanMapper.DEFAULT.map(source, new IncompatibleTarget(), BeanMapFlag.THROW_ON_MISS_MAPPING.flags()));
    }

    @Test
    public void convertFromObjectStringSplitsByCommaSpaceToken() {
        Map<String, Object> values = BeanMapper.convertFromObjectString(
                "OrderDTO(id=1, verifyDate=2022-07-30 15:35:34, extendMap={sendOrderId=2})", true);

        assertEquals("1", values.get("id"));
        assertEquals("2022-07-30 15:35:34", values.get("verifyDate"));
        assertEquals("2", ((Map) values.get("extendMap")).get("sendOrderId"));
    }

    public static class TrimFormatSource {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class TrimFormatTarget {
        private String info;

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    public static class SkipNullSource {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class SkipNullTarget {
        private String info;

        public String getInfo() {
            return info;
        }

        public void setInfo(String info) {
            this.info = info;
        }
    }

    public static class SameNameSource {
        private Integer index;
        private Integer index2;

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }

        public Integer getIndex2() {
            return index2;
        }

        public void setIndex2(Integer index2) {
            this.index2 = index2;
        }
    }

    public static class SameNameTarget {
        private Integer index;

        public Integer getIndex() {
            return index;
        }

        public void setIndex(Integer index) {
            this.index = index;
        }
    }

    public static class ConverterSource {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class ConverterTarget {
        private String converted;

        public String getConverted() {
            return converted;
        }

        public void setConverted(String converted) {
            this.converted = converted;
        }
    }

    public static class IncompatibleSource {
        private DateTime birth;

        public DateTime getBirth() {
            return birth;
        }

        public void setBirth(DateTime birth) {
            this.birth = birth;
        }
    }

    public static class IncompatibleTarget {
        private int birth;

        public int getBirth() {
            return birth;
        }

        public void setBirth(int birth) {
            this.birth = birth;
        }
    }
}
