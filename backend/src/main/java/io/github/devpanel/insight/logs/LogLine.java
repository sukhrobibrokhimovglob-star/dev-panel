package io.github.devpanel.insight.logs;

/** One log event as the live log view shows it. {@code error} is the stack trace, if any. */
public record LogLine(long seq, long time, String level, String logger, String thread,
                      String message, String traceId, String error) {
}
