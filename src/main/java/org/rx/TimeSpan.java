package org.rx;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Date;

/**
 * http://www.mkyong.com/java/how-to-calculate-date-time-difference-in-java/
 */
public final class TimeSpan implements Serializable {
    private long ticks;

    public long getTicks() {
        return ticks;
    }

    public void setTicks(long ticks) {
        this.ticks = ticks;
    }

    public TimeSpan(Date start, Date end) {
        this(start.getTime() - end.getTime());
    }

    public TimeSpan(long ticks) {
        this.ticks = ticks;
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
