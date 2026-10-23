package io.github.devpanel.audit;

import io.github.devpanel.audit.DevRequestAuditRepository.Filter;
import io.github.devpanel.audit.DevRequestAuditRepository.Page;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Filters the error audit, see {@link DevRequestAuditFilter} for what gets recorded. */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class DevAuditController {

  private final DevRequestAuditRepository repository;

  public DevAuditController(DevRequestAuditRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/audit")
  public Page filter(
      @RequestParam(required = false) String traceId,
      @RequestParam(required = false) String httpMethod,
      @RequestParam(required = false) String path,
      @RequestParam(required = false) Integer statusCode,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return repository.filter(new Filter(traceId, httpMethod, path, statusCode, from, to), page,
        size);
  }
}
