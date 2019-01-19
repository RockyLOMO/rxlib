package org.rx.common;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

import java.io.StringWriter;

import static org.rx.common.Contract.require;

public class LogWriter extends StringWriter {
    private org.slf4j.Logger log;
    @Getter
    @Setter
    private String prefix;

    public LogWriter() {
        this(Logger.log1);
    }

    public LogWriter(org.slf4j.Logger log) {
        require(log);

        this.log = log;
    }

    @Override
    public void write(String str) {
        super.write(prefix);
        super.write(" ");
        super.write(str);
    }

    @Override
    public void write(String str, int off, int len) {
        write(str.substring(off, off + len));
    }

    public LogWriter writeLine() {
        super.write(System.lineSeparator());
        return this;
    }

    public LogWriter info(Object obj) {
        write(String.valueOf(obj));
        writeLine();
        return this;
    }

    public LogWriter info(String format, Object... args) {
        write(String.format(format.replace("{}", "%s"), args));
        writeLine();
        return this;
    }

    public LogWriter infoAndFlush(Object obj) {
        info(obj).flush();
        return this;
    }

    public LogWriter infoAndFlush(String format, Object... args) {
        info(format, args).flush();
        return this;
    }

    public LogWriter error(String msg, Throwable e) {
        log.error(msg, e);
        return this;
    }

    @Override
    public void flush() {
        super.flush();
        StringBuffer buffer = super.getBuffer();
        log.info(buffer.toString());
        buffer.setLength(0);
    }

    @SneakyThrows
    @Override
    public void close() {
        this.flush();
        super.close();
    }
}
