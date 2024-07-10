package org.rx.bean;

import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.time.FastDateFormat;
import org.rx.annotation.ErrorCode;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.exception.ApplicationException;

import java.text.ParseException;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.values;

/**
 * GMT: UTC +0
 * http://www.mkyong.com/java/how-to-calculate-date-time-difference-in-java/
 */
@SuppressWarnings("deprecation")
public final class DateTime extends Date {
    private static final long serialVersionUID = 414744178681347341L;
    public static final String DATE_FORMAT = "yyy-MM-dd";
    public static final String TIME_FORMAT = "HH:mm:ss";
    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final String ISO_DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";
    //2020-02-02 14:00:00.001 会适配 yyyy-MM-dd HH:mm:ss
    public static final String[] FORMATS = new String[]{ISO_DATE_TIME_FORMAT, "yyyy-MM-dd HH:mm:ss.SSSZ", "yyyy-MM-dd HH:mm:ss.SSS",
            DATE_TIME_FORMAT, DATE_FORMAT, TIME_FORMAT,
            "yyyyMMddHHmmssSSS"};

    public static DateTime now() {
        return new DateTime(System.currentTimeMillis(), TimeZone.getDefault());
    }

    public static DateTime utcNow() {
        return new DateTime(System.currentTimeMillis(), TimeZone.getTimeZone("UTC"));
    }

    public static DateTime ofToNull(Date d) {
        return d != null ? of(d) : null;
    }

    public static DateTime of(@NonNull Date d) {
        return d instanceof DateTime ? (DateTime) d : new DateTime(d.getTime(), TimeZone.getDefault());
    }

    public static DateTime valueOf(@NonNull String dateString) {
        return valueOf(dateString, (TimeZone) null);
    }

    @ErrorCode(cause = ParseException.class)
    public static DateTime valueOf(@NonNull String dateString, TimeZone timeZone) {
        if (Strings.isNumeric(dateString)) {
            return new DateTime(Long.parseLong(dateString), timeZone);
        }
        Throwable lastEx = null;
        int offset = dateString.length() >= 23 ? 0 : 3;
        int len = offset + 3, fb = 6;
        for (int i = offset; i < len; i++) {
            try {
                return valueOf(dateString, FORMATS[i], timeZone);
            } catch (Throwable ex) {
                lastEx = ex;
            }
        }
        for (int i = fb; i < FORMATS.length; i++) {
            try {
                return valueOf(dateString, FORMATS[i], timeZone);
            } catch (Throwable ex) {
                lastEx = ex;
            }
        }
        throw new ApplicationException(values(String.join(",", FORMATS), dateString), lastEx);
    }

    public static DateTime valueOf(String dateString, String format) {
        return valueOf(dateString, format, null);
    }

    @SneakyThrows
    public static DateTime valueOf(String dateString, String format, TimeZone timeZone) {
        //SimpleDateFormat not thread safe
        return DateTime.of(FastDateFormat.getInstance(format, timeZone).parse(dateString));
    }

    private Calendar calendar;

    private Calendar getCalendar() {
        if (calendar == null) {
            calendar = Calendar.getInstance();
            calendar.setTimeInMillis(super.getTime());
        }
        return calendar;
    }

    @Override
    public int getYear() {
        return getCalendar().get(Calendar.YEAR);
    }

    @Override
    public int getMonth() {
        return getCalendar().get(Calendar.MONTH) + 1;
    }

    public Month getMonthEnum() {
        return Month.of(getCalendar().get(Calendar.MONTH) + 1);
    }

    @Override
    public int getDay() {
        return getCalendar().get(Calendar.DAY_OF_MONTH);
    }

    @Override
    public int getHours() {
        return getCalendar().get(Calendar.HOUR_OF_DAY);
    }

    @Override
    public int getMinutes() {
        return getCalendar().get(Calendar.MINUTE);
    }

    @Override
    public int getSeconds() {
        return getCalendar().get(Calendar.SECOND);
    }

    public int getMillisecond() {
        return getCalendar().get(Calendar.MILLISECOND);
    }

    public int getDayOfYear() {
        return getCalendar().get(Calendar.DAY_OF_YEAR);
    }

    public DayOfWeek getDayOfWeek() {
        switch (getCalendar().get(Calendar.DAY_OF_WEEK)) {
            case Calendar.MONDAY:
                return DayOfWeek.MONDAY;
            case Calendar.TUESDAY:
                return DayOfWeek.TUESDAY;
            case Calendar.WEDNESDAY:
                return DayOfWeek.WEDNESDAY;
            case Calendar.THURSDAY:
                return DayOfWeek.THURSDAY;
            case Calendar.FRIDAY:
                return DayOfWeek.FRIDAY;
            case Calendar.SATURDAY:
                return DayOfWeek.SATURDAY;
//            case Calendar.SUNDAY:
            default:
                return DayOfWeek.SUNDAY;
        }
    }

    public double getTotalDays() {
        return super.getTime() / (24 * 60 * 60 * 1000d);
    }

    public double getTotalHours() {
        return super.getTime() / (60 * 60 * 1000d);
    }

    public double getTotalMinutes() {
        return super.getTime() / (60 * 1000d);
    }

    public double getTotalSeconds() {
        return super.getTime() / (1000d);
    }

    public double getTotalMilliseconds() {
        return super.getTime();
    }

    public DateTime(int year, Month month, int day, int hour, int minute, int second, TimeZone zone) {
        Calendar c = getCalendar();
        c.set(year, month.getValue() - 1, day, hour, minute, second);
        super.setTime(c.getTimeInMillis());
        if (zone != null) {
            c.setTimeZone(zone);
        }
    }

    public DateTime(long ticks, TimeZone zone) {
        super(ticks);
        if (zone != null) {
            getCalendar().setTimeZone(zone);
        }
    }

    @Override
    public void setTime(long time) {
        super.setTime(time);
        if (calendar != null) {
            calendar.setTimeInMillis(time);
        }
    }

    public void setTimeZone(TimeZone zone) {
        getCalendar().setTimeZone(zone);
    }

    public TimeZone getTimeZone() {
        return getCalendar().getTimeZone();
    }

    public DateTime getDatePart() {
        return new DateTime(getYear(), getMonthEnum(), getDay(), 0, 0, 0, getTimeZone());
    }

    public DateTime setDatePart(int year, Month month, int day) {
        return new DateTime(year, month, day, getHours(), getMinutes(), getSeconds(), getTimeZone());
    }

    public DateTime setDatePart(String date) {
        return DateTime.valueOf(toString(date + " HH:mm:ss"), DATE_TIME_FORMAT, getTimeZone());
    }

    public DateTime getTimePart() {
        return new DateTime(1970, Month.JANUARY, 1, getHours(), getMinutes(), getSeconds(), getTimeZone());
    }

    public DateTime setTimePart(int hour, int minute, int second) {
        return new DateTime(getYear(), getMonthEnum(), getDay(), hour, minute, second, getTimeZone());
    }

    public DateTime setTimePart(String time) {
        return DateTime.valueOf(toString("yyy-MM-dd " + time), DATE_TIME_FORMAT, getTimeZone());
    }

//    public boolean isToday() {
//        DateTime n = DateTime.now();
//        n.setTimeZone(getTimeZone());
//        return n.getYear() == getYear() && n.getMonth() == getMonth() && n.getDay() == getDay();
//    }

    public DateTime addYears(int value) {
        return add(Calendar.YEAR, value);
    }

    public DateTime addMonths(int value) {
        return add(Calendar.MONTH, value);
    }

    public DateTime addDays(int value) {
        return add(Calendar.DAY_OF_MONTH, value);
    }

    public DateTime addHours(int value) {
        return add(Calendar.HOUR_OF_DAY, value);
    }

    public DateTime addMinutes(int value) {
        return add(Calendar.MINUTE, value);
    }

    public DateTime addSeconds(int value) {
        return add(Calendar.SECOND, value);
    }

    public DateTime addMilliseconds(int value) {
        return add(Calendar.MILLISECOND, value);
    }

    public DateTime nextDayOfWeek() {
//        System.out.println("n:" + LocalDate.parse(toDateString()).with(TemporalAdjusters.next(getDayOfWeek())));
//        int dayOfWeek = getCalendar().get(Calendar.DAY_OF_WEEK);
//        return addDays((9 - dayOfWeek) % 7);
        return addDays(7);
    }

    public DateTime lastDayOfMonth() {
//        System.out.println("n:" + LocalDate.parse(toDateString()).with(TemporalAdjusters.lastDayOfMonth()));
        return set(Calendar.DAY_OF_MONTH, getCalendar().getActualMaximum(Calendar.DAY_OF_MONTH));
    }

    private DateTime add(int field, int value) {
        Calendar c = getCalendar();
        long mark = c.getTimeInMillis();
        c.set(field, c.get(field) + value);
        try {
            return new DateTime(c.getTimeInMillis(), getTimeZone());
        } finally {
            c.setTimeInMillis(mark);
        }
    }

    private DateTime set(int field, int value) {
        Calendar c = getCalendar();
        long mark = c.getTimeInMillis();
        c.set(field, value);
        try {
            return new DateTime(c.getTimeInMillis(), getTimeZone());
        } finally {
            c.setTimeInMillis(mark);
        }
    }

    public DateTime add(@NonNull Date value) {
        return new DateTime(super.getTime() + value.getTime(), getTimeZone());
    }

    public DateTime subtract(@NonNull Date value) {
        return new DateTime(super.getTime() - value.getTime(), getTimeZone());
    }

    public String toDateString() {
        return toString(DATE_FORMAT);
    }

    public String toTimeString() {
        return toString(TIME_FORMAT);
    }

    public String toDateTimeString() {
        return toString(DATE_TIME_FORMAT);
    }

    @Override
    public String toString() {
        return toString(ifNull(RxConfig.INSTANCE.getDateFormat(), DATE_TIME_FORMAT));
    }

    public String toString(@NonNull String format) {
        return FastDateFormat.getInstance(format, getTimeZone()).format(this);
    }
}
