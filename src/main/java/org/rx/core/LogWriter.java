package org.rx.core;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.StringWriter;

import static org.rx.core.Contract.require;

@Slf4j
public class LogWriter extends StringWriter {
    private org.slf4j.Logger logRef;
    @Getter
    @Setter
    private String prefix;

    public LogWriter() {
        this(log);
    }

    public LogWriter(org.slf4j.Logger log) {
        require(log);

        this.logRef = log;
    }

    @Override
    public void write(@NotNull String str) {
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
        logRef.error(msg, e);
        return this;
    }

    @Override
    public void flush() {
        super.flush();
        StringBuffer buffer = super.getBuffer();
        logRef.info(buffer.toString());
        buffer.setLength(0);
    }

    @SneakyThrows
    @Override
    public void close() {
        this.flush();
        super.close();
    }
}
