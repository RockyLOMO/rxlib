package org.rx.beans;

import org.apache.commons.lang3.time.FastDateFormat;
import org.rx.annotation.ErrorCode;
import org.rx.core.Contract;
import org.rx.core.NQuery;
import org.rx.core.SystemException;

import java.text.ParseException;
import java.time.DayOfWeek;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import static org.rx.beans.$.$;
import static org.rx.core.Contract.require;
import static org.rx.core.Contract.values;

/**
 * http://www.mkyong.com/java/how-to-calculate-date-time-difference-in-java/
 */
public final class DateTime extends Date {
    public static final TimeZone UtcZone = TimeZone.getTimeZone("UTC");
    public static final DateTime BaseDate = new DateTime(2000, 1, 1);
    public static final NQuery<String> Formats = NQuery.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss,SSS",
            "yyyyMMddHHmmssSSS", "yyyy-MM-dd HH:mm:ss,SSSZ");

    public static DateTime now() {
        return new DateTime(System.currentTimeMillis());
    }

    public static DateTime utcNow() {
        return now().asUniversalTime();
    }

    @ErrorCode(cause = ParseException.class, messageKeys = {"$formats", "$date"})
    public static DateTime valueOf(String dateString) {
        SystemException lastEx = null;
        for (String format : Formats) {
            try {
                return valueOf(dateString, format);
            } catch (SystemException ex) {
                lastEx = ex;
            }
        }
        $<ParseException> out = $();
        Exception nested = lastEx.tryGet(out, ParseException.class) ? out.v : lastEx;
        throw new SystemException(values(String.join(",", Formats), dateString), nested);
    }

    public static DateTime valueOf(String dateString, String format) {
        try {
            //SimpleDateFormat not thread safe
            return new DateTime(FastDateFormat.getInstance(format).parse(dateString));
        } catch (ParseException ex) {
            throw SystemException.wrap(ex);
        }
    }

    private Calendar calendar;

    private Calendar getCalendar() {
        if (calendar == null) {
            calendar = Calendar.getInstance();
            calendar.setTimeInMillis(super.getTime());
        }
        return calendar;
    }

    public DateTime getDateComponent() {
        String format = "yyyyMMdd";
        return DateTime.valueOf(this.toString(format), format);
    }

    public DateTime getDateTimeComponent() {
        String format = "yyyyMMddHHmmss";
        return DateTime.valueOf(this.toString(format), format);
    }

    @SuppressWarnings(Contract.AllWarnings)
    @Override
    public int getYear() {
        return getCalendar().get(Calendar.YEAR);
    }

    @SuppressWarnings(Contract.AllWarnings)
    @Override
    public int getMonth() {
        return getCalendar().get(Calendar.MONTH) + 1;
    }

    @SuppressWarnings(Contract.AllWarnings)
    @Override
    public int getDay() {
        return getCalendar().get(Calendar.DAY_OF_MONTH);
    }

    @SuppressWarnings(Contract.AllWarnings)
    @Override
    public int getHours() {
        return getCalendar().get(Calendar.HOUR_OF_DAY);
    }

    @SuppressWarnings(Contract.AllWarnings)
    @Override
    public int getMinutes() {
        return getCalendar().get(Calendar.MINUTE);
    }

    @SuppressWarnings(Contract.AllWarnings)
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
        return DayOfWeek.of(getCalendar().get(Calendar.DAY_OF_WEEK));
    }

    public double getTotalDays() {
        return super.getTime() / (24 * 60 * 60 * 1000);
    }

    public double getTotalHours() {
        return super.getTime() / (60 * 60 * 1000);
    }

    public double getTotalMinutes() {
        return super.getTime() / (60 * 1000);
    }

    public double getTotalSeconds() {
        return super.getTime() / (1000);
    }

    public double getTotalMilliseconds() {
        return super.getTime();
    }

    public DateTime(int year, int month, int day) {
        this(year, month, day, 0, 0, 0);
    }

    public DateTime(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = getCalendar();
        c.set(year, month, day, hour, minute, second);
        super.setTime(c.getTimeInMillis());
    }

    public DateTime(long ticks) {
        super(ticks);
    }

    public DateTime(Date date) {
        super(date.getTime());
    }

    @Override
    public synchronized void setTime(long time) {
        super.setTime(time);
        if (calendar != null) {
            calendar.setTimeInMillis(time);
        }
    }

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

    private DateTime add(int field, int value) {
        Calendar c = getCalendar();
        long mark = c.getTimeInMillis();
        c.set(field, c.get(field) + value);
        try {
            return new DateTime(c.getTimeInMillis());
        } finally {
            c.setTimeInMillis(mark);
        }
    }

    public DateTime addTicks(long ticks) {
        return new DateTime(super.getTime() + ticks);
    }

    public DateTime add(Date value) {
        require(value);

        return addTicks(value.getTime());
    }

    public DateTime subtract(Date value) {
        require(value);

        return new DateTime(super.getTime() - value.getTime());
    }

    public DateTime asLocalTime() {
        getCalendar().setTimeZone(TimeZone.getDefault());
        return this;
    }

    public DateTime asUniversalTime() {
        getCalendar().setTimeZone(UtcZone);
        return this;
    }

    public String toDateString() {
        return toString("yyyy-MM-dd");
    }

    public String toDateTimeString() {
        return toString(Formats.first());
    }

    @Override
    public String toString() {
        return toString(Formats.last());
    }

    public String toString(String format) {
        require(format);

        return FastDateFormat.getInstance(format, getCalendar().getTimeZone()).format(this);
    }
}
