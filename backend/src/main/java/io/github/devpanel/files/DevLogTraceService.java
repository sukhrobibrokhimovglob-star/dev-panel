package io.github.devpanel.files;

import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.support.TraceIds;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Finds the log entries of one request in the logs folder (live {@code .log} files and rolled
 * {@code .log.gz} archives). It only finds something when the log pattern prints the trace id.
 *
 * <p>An entry belongs to the trace when its first line contains the id. Lines that do not start
 * with a timestamp (stack traces) belong to the entry above them.
 *
 * <p>Files are read newest first. A request's entries sit in one file, or in two adjacent ones when
 * the log rolled mid-request, so the search stops at the first file without a match after one with
 * a match. Files last modified well before {@code from} cannot hold the request and are skipped;
 * the {@link #FROM_MARGIN} keeps the file that rolled while a long request was still running.
 */
public class DevLogTraceService {

  static final int MAX_ENTRIES = 1000;

  private static final Duration FROM_MARGIN = Duration.ofHours(1);

  private static final Pattern ENTRY_START =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}");

  private final Path logsDir;

  public DevLogTraceService(Path logsDir) {
    this.logsDir = logsDir;
  }

  /**
   * @param from when the request was received (server local time, as in the audit); optional, it
   *             only narrows the files that are read
   */
  public LogTrace find(String traceId, LocalDateTime from) {
    if (!TraceIds.isWellFormed(traceId)) {
      throw DevPanelException.badRequest("Invalid trace id");
    }
    Instant notBefore = from == null ? null : from.atZone(ZoneId.systemDefault()).toInstant()
        .minus(FROM_MARGIN);

    List<List<Entry>> perFile = new ArrayList<>();
    int total = 0;
    boolean truncated = false;
    for (Path file : logFilesNewestFirst(notBefore)) {
      List<Entry> entries = search(file, traceId, MAX_ENTRIES - total);
      if (entries.isEmpty()) {
        if (!perFile.isEmpty()) {
          break;
        }
        continue;
      }
      perFile.add(0, entries);
      total += entries.size();
      if (total >= MAX_ENTRIES) {
        truncated = true;
        break;
      }
    }
    return new LogTrace(traceId, perFile.stream().flatMap(List::stream).toList(), truncated,
        "files");
  }

  private List<Path> logFilesNewestFirst(Instant notBefore) {
    if (!Files.isDirectory(logsDir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(logsDir)) {
      return walk.filter(Files::isRegularFile)
          .filter(DevLogTraceService::isLogFile)
          .filter(file -> notBefore == null || !lastModified(file).isBefore(notBefore))
          .sorted(Comparator.comparing(DevLogTraceService::lastModified).reversed())
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not list " + logsDir, e);
    }
  }

  /** At most {@code limit} entries of the trace in one file, in file order. */
  private List<Entry> search(Path file, String traceId, int limit) {
    String relativePath = logsDir.relativize(file).toString().replace('\\', '/');
    List<Entry> entries = new ArrayList<>();
    try (BufferedReader reader = open(file)) {
      StringBuilder current = null;
      int currentLine = 0;
      int lineNumber = 0;
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (!ENTRY_START.matcher(line).find()) {
          if (current != null) {
            current.append('\n').append(line);
          }
          continue;
        }
        if (current != null) {
          entries.add(new Entry(relativePath, currentLine, current.toString()));
          current = null;
          if (entries.size() >= limit) {
            return entries;
          }
        }
        if (line.contains(traceId)) {
          current = new StringBuilder(line);
          currentLine = lineNumber;
        }
      }
      if (current != null) {
        entries.add(new Entry(relativePath, currentLine, current.toString()));
      }
      return entries;
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file, e);
    }
  }

  /** Malformed bytes (a line cut by a crash) are replaced rather than failing the whole search. */
  private static BufferedReader open(Path file) throws IOException {
    InputStream in = Files.newInputStream(file);
    if (file.getFileName().toString().endsWith(".gz")) {
      try {
        in = new GZIPInputStream(in);
      } catch (IOException e) {
        in.close();
        throw e;
      }
    }
    return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
  }

  private static boolean isLogFile(Path file) {
    String name = file.getFileName().toString();
    return name.endsWith(".log") || name.endsWith(".log.gz");
  }

  private static Instant lastModified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toInstant();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file, e);
    }
  }

  public record Entry(String file, int line, String text) {
  }

  /** {@code source}: "files" (the log folder) or "memory" (the panel's recent lines). */
  public record LogTrace(String traceId, List<Entry> entries, boolean truncated, String source) {
  }
}
