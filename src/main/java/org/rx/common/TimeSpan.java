package org.rx.common;

import java.util.Calendar;
import java.util.Date;

/**
 * Created by wangxiaoming on 2016/2/23.
 * http://www.mkyong.com/java/how-to-calculate-date-time-difference-in-java/
 */
public class TimeSpan {
    private long ticks;

    public TimeSpan(long ticks) {
        this.ticks = ticks;
    }

    public TimeSpan(Date start, Date end) {
        ticks = start.getTime() - end.getTime();
    }

    public long getTicks() {
        return ticks;
    }

    public void setTicks(long value) {
        this.ticks = value;
    }

    public long getDays() {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(ticks));
        return c.get(Calendar.DAY_OF_MONTH);
    }

    public long getTotalDays() {
        return ticks / (24 * 60 * 60 * 1000);
    }

    public long getTotalHours() {
        return ticks / (60 * 60 * 1000);
    }

    public long getTotalMinutes() {
        return ticks / (60 * 1000);
    }

    public long getTotalSeconds() {
        return ticks / (1000);
    }

    public long getTotalMilliseconds() {
        return ticks;
    }
}
