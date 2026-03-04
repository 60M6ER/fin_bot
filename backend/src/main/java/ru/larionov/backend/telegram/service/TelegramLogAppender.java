package ru.larionov.backend.telegram.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class TelegramLogAppender extends AppenderBase<ILoggingEvent> {

    private static final ThreadLocal<Boolean> sending = ThreadLocal.withInitial(() -> false);

    @Override
    protected void append(ILoggingEvent event) {
        if (sending.get()) return;
        if (event.getLevel() != Level.ERROR) return;

        var ctx = SpringContextHolder.get();
        if (ctx == null) return;

        try {
            sending.set(true);
            var notifyService = ctx.getBean(TelegramNotifyService.class);

            String message = """
                    🚨 ERROR

                    Logger: %s
                    Message: %s
                    """.formatted(
                    event.getLoggerName(),
                    event.getFormattedMessage()
            );

            notifyService.broadcast(message);
        } finally {
            sending.set(false);
        }
    }
}
