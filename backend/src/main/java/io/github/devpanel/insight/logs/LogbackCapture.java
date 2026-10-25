package io.github.devpanel.insight.logs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

/**
 * Feeds {@link LogEvents} from Logback (Spring Boot's default), as one more appender on the root
 * logger: whatever the application logs still goes where it went before. Only created when
 * Logback is the logging backend.
 */
public class LogbackCapture extends AppenderBase<ILoggingEvent> {

  private static final String NAME = "DEVPANEL";
  private static final int MAX_FRAMES = 30;

  private final LogEvents events;
  private final String mdcKey;

  public LogbackCapture(LogEvents events, String mdcKey) {
    this.events = events;
    this.mdcKey = mdcKey;
    setName(NAME);
  }

  /** @return false when Logback is not the backend in use */
  public boolean install() {
    if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
      return false;
    }
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    if (root.getAppender(NAME) == this) {
      return true;
    }
    // A capture of an earlier context in this JVM (a restart, another test) hands over
    root.detachAppender(NAME);
    setContext(context);
    start();
    root.addAppender(this);
    return true;
  }

  public void uninstall() {
    if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
      context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this);
    }
    stop();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event.getLevel().toInt() < Level.INFO_INT && event.getLevel() != Level.DEBUG) {
      return;
    }
    IThrowableProxy proxy = event.getThrowableProxy();
    Throwable throwable = proxy instanceof ThrowableProxy real ? real.getThrowable() : null;
    events.accept(event.getTimeStamp(), event.getLevel().toString(), event.getLoggerName(),
        event.getThreadName(), event.getFormattedMessage(),
        event.getMDCPropertyMap().get(mdcKey), throwable, proxy == null ? null : text(proxy));
  }

  private static String text(IThrowableProxy proxy) {
    StringBuilder text = new StringBuilder();
    IThrowableProxy current = proxy;
    for (int depth = 0; current != null && depth < 4; depth++) {
      text.append(depth == 0 ? "" : "Caused by: ").append(current.getClassName()).append(": ")
          .append(current.getMessage()).append('\n');
      StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
      for (int i = 0; i < Math.min(frames.length, MAX_FRAMES); i++) {
        text.append('\t').append(frames[i].getSTEAsString()).append('\n');
      }
      current = current.getCause();
    }
    return text.toString();
  }
}
