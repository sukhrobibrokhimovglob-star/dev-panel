package io.github.devpanel.insight.profiler;

import io.github.devpanel.insight.trace.CallSites;
import io.github.devpanel.support.DevPanelException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A sampling profiler: every {@value #INTERVAL_MS} ms for a few seconds it looks at what the
 * running threads execute and adds their stacks into a tree, the flame graph. A method that is
 * on the stack in 40% of the samples takes about 40% of the time. Nothing is instrumented; the
 * cost is the sampling thread itself (about one core's few percent), only while it runs, and
 * one profile at a time.
 */
public class Profiler {

  static final int INTERVAL_MS = 20;
  private static final int MAX_DEPTH = 160;
  private static final int MAX_NODES = 30_000;
  private static final int KEEP = 5;
  /** Request threads of Tomcat, Jetty and Undertow. */
  private static final Pattern REQUEST_THREAD = Pattern.compile(
      "(?i).*(http-|https-|-exec-|qtp|xnio|undertow|task-).*");
  /** Top frames of a thread that is "runnable" but only waits for the network. */
  private static final List<String> WAITING_FRAMES = List.of("sun.nio.ch.", "java.net.Socket",
      "sun.nio.ch.SocketDispatcher", "java.net.PlainSocketImpl", "sun.nio.ch.EPoll",
      "sun.nio.ch.KQueue", "org.apache.tomcat.util.net.NioEndpoint$Poller",
      "sun.nio.ch.Net.poll", "java.io.FileInputStream.readBytes");

  private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
  private final Deque<Profile> profiles = new ArrayDeque<>();
  private volatile Running running;

  /** @param scope "requests" (request threads only) or "all" */
  public synchronized Profile start(int seconds, String scope, String by) {
    if (running != null) {
      throw DevPanelException.badRequest("A profile is already being recorded");
    }
    int duration = Math.max(3, Math.min(seconds, 60));
    boolean all = "all".equalsIgnoreCase(scope);
    Running run = new Running(UUID.randomUUID().toString(), Instant.now(), duration, all, by);
    running = run;
    Thread thread = new Thread(() -> record(run), "devpanel-profiler");
    thread.setDaemon(true);
    thread.start();
    return run.snapshot("RUNNING");
  }

  public synchronized List<Profile> list() {
    List<Profile> list = new ArrayList<>();
    Running current = running;
    if (current != null) {
      list.add(current.snapshot("RUNNING"));
    }
    list.addAll(profiles);
    return list;
  }

  public synchronized Profile find(String id) {
    Running current = running;
    if (current != null && current.id.equals(id)) {
      return current.snapshot("RUNNING");
    }
    return profiles.stream().filter(profile -> profile.id().equals(id)).findFirst()
        .orElseThrow(() -> DevPanelException.notFound("No such profile"));
  }

  private void record(Running run) {
    long end = System.currentTimeMillis() + run.seconds * 1000L;
    long own = Thread.currentThread().getId();
    try {
      while (System.currentTimeMillis() < end) {
        long started = System.nanoTime();
        sample(run, own);
        long sleep = INTERVAL_MS - (System.nanoTime() - started) / 1_000_000;
        if (sleep > 0) {
          Thread.sleep(sleep);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      run.error = e.getClass().getSimpleName() + ": " + e.getMessage();
    } finally {
      synchronized (this) {
        profiles.addFirst(run.snapshot("DONE"));
        while (profiles.size() > KEEP) {
          profiles.removeLast();
        }
        running = null;
      }
    }
  }

  private void sample(Running run, long own) {
    ThreadInfo[] infos = threads.dumpAllThreads(false, false, MAX_DEPTH);
    run.samples++;
    for (ThreadInfo info : infos) {
      if (info == null || info.getThreadId() == own
          || info.getThreadState() != Thread.State.RUNNABLE
          || (!run.all && !REQUEST_THREAD.matcher(info.getThreadName()).matches())) {
        continue;
      }
      StackTraceElement[] stack = info.getStackTrace();
      if (stack.length == 0 || waitingOnNetwork(stack[0])) {
        continue;
      }
      run.threadSamples++;
      run.threadNames.add(info.getThreadName());
      synchronized (run) {
        Node node = run.root;
        node.total++;
        for (int i = stack.length - 1; i >= 0; i--) {
          StackTraceElement frame = stack[i];
          String name = frame.getClassName() + "." + frame.getMethodName();
          Node child = node.children.get(name);
          if (child == null) {
            if (run.nodes >= MAX_NODES) {
              break;
            }
            child = new Node(name, CallSites.isApplication(frame.getClassName()));
            node.children.put(name, child);
            run.nodes++;
          }
          child.total++;
          node = child;
        }
        node.self++;
      }
    }
  }

  private static boolean waitingOnNetwork(StackTraceElement top) {
    String frame = top.getClassName() + "." + top.getMethodName();
    for (String waiting : WAITING_FRAMES) {
      if (frame.startsWith(waiting)) {
        return true;
      }
    }
    return false;
  }

  private static final class Running {

    final String id;
    final Instant startedAt;
    final int seconds;
    final boolean all;
    final String by;
    final Node root = new Node("all", false);
    final java.util.Set<String> threadNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
    int samples;
    long threadSamples;
    int nodes;
    volatile String error;

    Running(String id, Instant startedAt, int seconds, boolean all, String by) {
      this.id = id;
      this.startedAt = startedAt;
      this.seconds = seconds;
      this.all = all;
      this.by = by;
    }

    synchronized Profile snapshot(String status) {
      long minimum = Math.max(1, root.total / 500);
      return new Profile(id, startedAt, seconds, all ? "all" : "requests", by, status, samples,
          threadSamples, threadNames.size(), root.view(minimum), hottest(root), error);
    }
  }

  /** Methods by their own time (on top of the stack), the application's first. */
  private static List<Hot> hottest(Node root) {
    Map<String, long[]> self = new HashMap<>();
    Map<String, Boolean> app = new HashMap<>();
    Deque<Node> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      Node node = stack.pop();
      if (node.self > 0 && node != root) {
        self.computeIfAbsent(node.name, key -> new long[2])[0] += node.self;
      }
      if (node != root) {
        self.computeIfAbsent(node.name, key -> new long[2])[1] += node.total;
        app.put(node.name, node.application);
      }
      node.children.values().forEach(stack::push);
    }
    List<Hot> hot = new ArrayList<>();
    self.forEach((name, counts) -> hot.add(new Hot(name, counts[0], counts[1],
        app.getOrDefault(name, false))));
    hot.sort(Comparator.comparingLong(Hot::self).reversed());
    return hot.subList(0, Math.min(40, hot.size()));
  }

  private static final class Node {

    final String name;
    final boolean application;
    final Map<String, Node> children = new LinkedHashMap<>();
    long total;
    long self;

    Node(String name, boolean application) {
      this.name = name;
      this.application = application;
    }

    /** Small branches (below {@code minimum} samples) are folded into "(other)". */
    FlameNode view(long minimum) {
      List<FlameNode> kids = new ArrayList<>();
      long folded = 0;
      for (Node child : children.values()) {
        if (child.total >= minimum) {
          kids.add(child.view(minimum));
        } else {
          folded += child.total;
        }
      }
      kids.sort(Comparator.comparingLong(FlameNode::total).reversed());
      if (folded > 0) {
        kids.add(new FlameNode("(other)", folded, 0, false, List.of()));
      }
      return new FlameNode(name, total, self, application, kids);
    }
  }

  public record FlameNode(String name, long total, long self, boolean application,
                          List<FlameNode> children) {
  }

  /** {@code self}: samples on top of the stack; {@code total}: samples anywhere on it. */
  public record Hot(String method, long self, long total, boolean application) {
  }

  /**
   * {@code samples}: sampling rounds; {@code threadSamples}: running stacks seen (the flame graph's
   * 100%).
   */
  public record Profile(String id, Instant startedAt, int seconds, String scope, String by,
                        String status, int samples, long threadSamples, int threads,
                        FlameNode root, List<Hot> hottest, String error) {
  }
}
