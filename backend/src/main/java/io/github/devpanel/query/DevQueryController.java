package io.github.devpanel.query;

import io.github.devpanel.query.DevQueryService.SchemaTable;
import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ad-hoc SQL. The query endpoints take only {@code SELECT} statements, run in a read-only
 * transaction by {@link DevQueryService}; although they are POSTs (the SQL goes in the body) any
 * account may use them. {@code /query/update} writes, so it needs a read-write account and
 * {@code devpanel.query.update-enabled=true}.
 */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class DevQueryController {

  private static final MediaType XLSX = MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  private static final DateTimeFormatter FILE_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private final DevQueryService queryService;
  private final ObjectProvider<DevQueryExcelWriter> excelWriter;

  public DevQueryController(DevQueryService queryService,
      ObjectProvider<DevQueryExcelWriter> excelWriter) {
    this.queryService = queryService;
    this.excelWriter = excelWriter;
  }

  @PostMapping("/query")
  public List<Map<String, Object>> runQuery(@RequestBody Map<String, String> body) {
    return queryService.run(queryOf(body));
  }

  /** The same query as {@link #runQuery}, as an .xlsx with the column names as the header row. */
  @PostMapping("/query/excel")
  public ResponseEntity<byte[]> runQueryToExcel(@RequestBody Map<String, String> body) {
    DevQueryExcelWriter writer = excelWriter.getIfAvailable();
    if (writer == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED,
          "Excel export needs Apache POI (org.apache.poi:poi-ooxml) on the classpath");
    }
    byte[] workbook = queryService.runWith(queryOf(body), writer);
    String filename = "query-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".xlsx";
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .body(workbook);
  }

  @PostMapping("/query/update")
  public UpdateResult runUpdate(@RequestBody Map<String, String> body) {
    return new UpdateResult(queryService.update(queryOf(body)));
  }

  /** Tables and columns the SQL editor offers as completions. */
  @GetMapping("/query/schema")
  public List<SchemaTable> schema() {
    return queryService.schema();
  }

  private static String queryOf(Map<String, String> body) {
    String query = body == null ? null : body.get("query");
    if (query == null) {
      throw DevPanelException.badRequest("Query cannot be null");
    }
    return query;
  }

  public record UpdateResult(int updatedRows) {
  }
}
