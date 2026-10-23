package io.github.devpanel.files;

import io.github.devpanel.files.DevFileBrowserService.FileEntry;
import io.github.devpanel.files.DevFileBrowserService.Folder;
import io.github.devpanel.files.DevLogTraceService.LogTrace;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
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

  private final DevFileBrowserService fileBrowserService;
  private final DevLogTraceService logTraceService;

  public DevFilesController(DevFileBrowserService fileBrowserService,
      DevLogTraceService logTraceService) {
    this.fileBrowserService = fileBrowserService;
    this.logTraceService = logTraceService;
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
    return logTraceService.find(traceId, from);
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
