package io.github.devpanel.insight.server;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/**
 * How close Tomcat is to its limits: request threads busy vs the maximum, requests waiting in
 * the queue, open connections vs the maximum. When all threads are busy new requests wait,
 * whatever the CPU says. Only for the embedded Tomcat.
 */
public class TomcatCapacity {

  private final ServletWebServerApplicationContext context;

  public TomcatCapacity(ServletWebServerApplicationContext context) {
    this.context = context;
  }

  /** Null when the server is not an embedded Tomcat or not started yet. */
  public State state() {
    WebServer server = context.getWebServer();
    if (!(server instanceof TomcatWebServer tomcat)) {
      return null;
    }
    Connector connector = tomcat.getTomcat().getConnector();
    if (connector == null) {
      return null;
    }
    ProtocolHandler handler = connector.getProtocolHandler();
    Executor executor = handler.getExecutor();
    int busy = -1;
    int poolSize = -1;
    int maxThreads = -1;
    int queued = -1;
    if (executor instanceof ThreadPoolExecutor pool) {
      busy = pool.getActiveCount();
      poolSize = pool.getPoolSize();
      maxThreads = pool.getMaximumPoolSize();
      queued = pool.getQueue().size();
    } else if (executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool) {
      // Tomcat 9.0.5x+ ships its own pool class, no longer a JDK ThreadPoolExecutor
      busy = pool.getActiveCount();
      poolSize = pool.getPoolSize();
      maxThreads = pool.getMaximumPoolSize();
      queued = pool.getQueue().size();
    }
    long connections = -1;
    int maxConnections = -1;
    if (handler instanceof AbstractProtocol<?> protocol) {
      connections = protocol.getConnectionCount();
      maxConnections = protocol.getMaxConnections();
      if (maxThreads < 0) {
        maxThreads = protocol.getMaxThreads();
      }
    }
    return new State(busy, poolSize, maxThreads, queued, connections, maxConnections);
  }

  /** -1 where Tomcat does not report a value. */
  public record State(int busyThreads, int poolThreads, int maxThreads, int queued,
                      long connections, int maxConnections) {
  }
}
