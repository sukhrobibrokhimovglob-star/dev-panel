package io.github.devpanel.query;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.audit.DevActionLog;
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
import org.springframework.web.bind.annotation.RequestParam;
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
  private final DataBrowser dataBrowser;
  private final DevActionLog actions;

  public DevQueryController(DevQueryService queryService,
      ObjectProvider<DevQueryExcelWriter> excelWriter, DataBrowser dataBrowser,
      DevActionLog actions) {
    this.queryService = queryService;
    this.excelWriter = excelWriter;
    this.dataBrowser = dataBrowser;
    this.actions = actions;
  }

  @PostMapping("/query")
  public List<Map<String, Object>> runQuery(DevAccount actor,
      @RequestBody Map<String, String> body) {
    List<Map<String, Object>> rows = queryService.run(queryOf(body));
    actions.record(actor, "query.select", null, queryOf(body));
    return rows;
  }

  /** Read-only table browsing; {@code filter}: column:operator:value, repeatable. */
  @GetMapping("/data/tables")
  public List<SchemaTable> dataTables() {
    return dataBrowser.tables();
  }

  @GetMapping("/data/rows")
  public DataBrowser.Page dataRows(DevAccount actor, @RequestParam String schema,
      @RequestParam String table, @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size, @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "false") boolean desc,
      @RequestParam(required = false) List<String> filter) {
    DataBrowser.Page result = dataBrowser.rows(schema, table, page, size, sort, desc, filter);
    if (page == 0) {
      actions.record(actor, "data.browse", schema + "." + table,
          filter == null ? null : String.join("; ", filter));
    }
    return result;
  }

  /** The same query as {@link #runQuery}, as an .xlsx with the column names as the header row. */
  @PostMapping("/query/excel")
  public ResponseEntity<byte[]> runQueryToExcel(DevAccount actor,
      @RequestBody Map<String, String> body) {
    DevQueryExcelWriter writer = excelWriter.getIfAvailable();
    if (writer == null) {
      throw new DevPanelException(HttpStatus.NOT_IMPLEMENTED,
          "Excel export needs Apache POI (org.apache.poi:poi-ooxml) on the classpath");
    }
    byte[] workbook = queryService.runWith(queryOf(body), writer);
    actions.record(actor, "query.excel", null, queryOf(body));
    String filename = "query-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".xlsx";
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .body(workbook);
  }

  @PostMapping("/query/update")
  public UpdateResult runUpdate(DevAccount actor, @RequestBody Map<String, String> body) {
    UpdateResult result = new UpdateResult(queryService.update(queryOf(body)));
    actions.record(actor, "query.update", null,
        result.updatedRows() + " rows: " + queryOf(body));
    return result;
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
