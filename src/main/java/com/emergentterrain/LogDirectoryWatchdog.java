package com.emergentterrain;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;

@Component
public class LogDirectoryWatchdog {

    private static final Logger log = LoggerFactory.getLogger(LogDirectoryWatchdog.class);

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void ensureLogDir() {
        if (Files.isDirectory(EmergentTerrainApplication.LOG_DIR)) return;
        try {
            Files.createDirectories(EmergentTerrainApplication.LOG_DIR);
        } catch (IOException e) {
            log.warn("LogDirectoryWatchdog: could not create {}: {}",
                    EmergentTerrainApplication.LOG_DIR, e.getMessage());
            return;
        }
        reopenFileAppenders();
        log.info("LogDirectoryWatchdog: recreated {} and reopened file appender",
                EmergentTerrainApplication.LOG_DIR);
    }

    private void reopenFileAppenders() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            Appender<ILoggingEvent> appender = it.next();
            if (appender instanceof FileAppender<ILoggingEvent>) {
                appender.stop();
                appender.start();
            }
        }
    }
}
