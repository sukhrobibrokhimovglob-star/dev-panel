package io.github.devpanel.insight.logs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams log lines to a browser (server-sent events). Logging threads only drop lines into a
 * bounded queue, never wait for a slow browser; a panel thread sends them in batches twice a
 * second. When the browser cannot keep up, lines are dropped rather than slowing the application.
 */
public class LiveLogs {

  private static final long TIMEOUT_MS = 30 * 60_000L;
  private static final int QUEUE = 2000;

  private final LogEvents events;
  private final ScheduledExecutorService executor;

  public LiveLogs(LogEvents events, ScheduledExecutorService executor) {
    this.events = events;
    this.executor = executor;
  }

  public SseEmitter open(String level, String text) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    BlockingQueue<LogLine> queue = new ArrayBlockingQueue<>(QUEUE);
    queue.addAll(events.recent(level, text, 200));
    Runnable unsubscribe = events.subscribe(line -> {
      if (LogEvents.matches(line, level, text)) {
        queue.offer(line);
      }
    });
    ScheduledFuture<?>[] pump = new ScheduledFuture<?>[1];
    Runnable close = () -> {
      unsubscribe.run();
      if (pump[0] != null) {
        pump[0].cancel(false);
      }
    };
    pump[0] = executor.scheduleWithFixedDelay(() -> {
      List<LogLine> batch = new ArrayList<>();
      queue.drainTo(batch, 500);
      try {
        // An empty batch is the heartbeat that keeps proxies from closing the stream
        emitter.send(SseEmitter.event().name("lines").data(batch));
      } catch (IOException | IllegalStateException e) {
        close.run();
      }
    }, 0, 500, TimeUnit.MILLISECONDS);
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    return emitter;
  }
}
