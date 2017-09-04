package org.rx.common;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateTime extends Date {
    //2000-01-01
    public static final Date BaseDate = new Date(100, 0, 1);
    private static final String DefaultFormat = "yyyy-MM-dd HH:mm:ss";
    private Calendar cal;

    private Calendar getCalendar() {
        if (cal == null) {
            cal = Calendar.getInstance();
        }
        cal.setTime(this);
        return cal;
    }

    public DateTime() {
        super();
    }

    public DateTime(String sDate) throws ParseException {
        this(sDate, DefaultFormat);
    }

    public DateTime(String sDate, String format) throws ParseException {
        this(new SimpleDateFormat(format).parse(sDate));
    }

    public DateTime(long ticks) {
        super(ticks);
    }

    public DateTime(Date date) {
        super(date.getTime());
    }

    public DateTime addYears(int value) {
        return add(Calendar.YEAR, value);
    }

    public DateTime addDays(int value) {
        return add(Calendar.DAY_OF_MONTH, value);
    }

    public DateTime addHours(int value) {
        return add(Calendar.HOUR_OF_DAY, value);
    }

    public DateTime addMinute(int value) {
        return add(Calendar.MINUTE, value);
    }

    public DateTime addSecond(int value) {
        return add(Calendar.SECOND, value);
    }

    public DateTime addMilliseconds(int value) {
        return add(Calendar.MILLISECOND, value);
    }

    private DateTime add(int field, int value) {
        Calendar c = getCalendar();
        c.set(field, c.get(field) + value);
        this.setTime(c.getTime().getTime());
        return this;
    }

    public TimeSpan subtract(Date value) {
        return new TimeSpan(this.getTime() - value.getTime());
    }

    @Override
    public String toString() {
        return toString(DefaultFormat);
    }

    public String toString(String format) {
        return new SimpleDateFormat(format).format(this);
    }
}
