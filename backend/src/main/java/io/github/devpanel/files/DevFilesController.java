package io.github.devpanel.files;

import io.github.devpanel.files.DevFileBrowserService.FileEntry;
import io.github.devpanel.files.DevFileBrowserService.Folder;
import io.github.devpanel.files.DevLogTraceService.LogTrace;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import io.github.devpanel.insight.logs.LogEvents;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only browsing of the docs and logs folders. See {@link DevFileBrowserService} for the
 * path-traversal guard applied to every {@code path} parameter.
 */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class DevFilesController {

  private static final java.time.format.DateTimeFormatter TIME =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
          .withZone(java.time.ZoneId.systemDefault());

  private final DevFileBrowserService fileBrowserService;
  private final DevLogTraceService logTraceService;
  private final ObjectProvider<LogEvents> logEvents;

  public DevFilesController(DevFileBrowserService fileBrowserService,
      DevLogTraceService logTraceService, ObjectProvider<LogEvents> logEvents) {
    this.fileBrowserService = fileBrowserService;
    this.logTraceService = logTraceService;
    this.logEvents = logEvents;
  }

  @GetMapping("/docs")
  public List<FileEntry> listDocs() {
    return fileBrowserService.list(Folder.DOCS);
  }

  @GetMapping("/docs/download")
  public ResponseEntity<Resource> downloadDoc(@RequestParam String path) throws IOException {
    return download(fileBrowserService.download(Folder.DOCS, path));
  }

  @GetMapping("/logs")
  public List<FileEntry> listLogs() {
    return fileBrowserService.list(Folder.LOGS);
  }

  @GetMapping("/logs/download")
  public ResponseEntity<Resource> downloadLog(@RequestParam String path) throws IOException {
    return download(fileBrowserService.download(Folder.LOGS, path));
  }

  /** {@code from}: the request's {@code occurredAt} from the audit, to skip older files. */
  @GetMapping("/logs/trace/{traceId}")
  public LogTrace findLogsByTrace(@PathVariable String traceId,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from) {
    LogTrace found = logTraceService.find(traceId, from);
    LogEvents events = logEvents.getIfAvailable();
    if (!found.entries().isEmpty() || events == null) {
      return found;
    }
    // The log files do not print the trace id (or were rotated): the panel's own recent lines
    List<DevLogTraceService.Entry> entries = events.byTrace(traceId).stream()
        .map(line -> new DevLogTraceService.Entry("memory", (int) line.seq(),
            TIME.format(java.time.Instant.ofEpochMilli(line.time())) + " " + line.level() + " ["
                + line.thread() + "] " + line.logger() + " - " + line.message()
                + (line.error() == null ? "" : "\n" + line.error())))
        .toList();
    return new LogTrace(traceId, entries, false, entries.isEmpty() ? "files" : "memory");
  }

  private static ResponseEntity<Resource> download(Resource resource) throws IOException {
    String probed = Files.probeContentType(resource.getFile().toPath());
    return ResponseEntity.ok()
        .contentType(probed != null ? MediaType.parseMediaType(probed) : MediaType.TEXT_PLAIN)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline().filename(resource.getFilename()).build().toString())
        .body(resource);
  }
}
