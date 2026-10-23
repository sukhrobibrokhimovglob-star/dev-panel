package io.github.devpanel.support;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Where the trace id of a request is found, whether {@code DevPanelTraceIdFilter} or the
 * application's own filter assigned it: the response header, the request attribute, the MDC.
 */
public final class TraceIds {

  public static final String REQUEST_ATTRIBUTE = TraceIds.class.getName() + ".TRACE_ID";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

  private static volatile String header = "X-Trace-Id";
  private static volatile String mdcKey = "traceId";

  private TraceIds() {
  }

  static void configure(String headerName, String mdcKeyName) {
    header = headerName;
    mdcKey = mdcKeyName;
  }

  public static String header() {
    return header;
  }

  public static String mdcKey() {
    return mdcKey;
  }

  public static boolean isWellFormed(String traceId) {
    return traceId != null && SAFE.matcher(traceId).matches();
  }

  public static String of(HttpServletRequest request, HttpServletResponse response) {
    String fromResponse = response == null ? null : response.getHeader(header);
    if (isWellFormed(fromResponse)) {
      return fromResponse;
    }
    if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String traceId) {
      return traceId;
    }
    return MDC.get(mdcKey);
  }

  /** Called once at startup with the configured names. */
  public static final class Configurer {

    public Configurer(String headerName, String mdcKeyName) {
      configure(headerName, mdcKeyName);
    }
  }
}
