package io.github.devpanel.web;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The panel's page. {@code <base-path>/} shows the UI; {@code <base-path>} without the slash
 * redirects there, because the UI's relative {@code api} and {@code assets/} URLs need the slash.
 * Both URLs reach this one method (Spring matches an optional trailing slash). The redirect's
 * {@code Location} is relative ({@code dev-panel/}), so it also works behind a proxy that adds a
 * prefix the application does not know about, such as nginx serving the backend under
 * {@code /api}.
 */
@Controller
public class DevPanelUiController {

  private final String basePath;

  public DevPanelUiController(@Value("${devpanel.base-path:/dev-panel}") String basePath) {
    this.basePath = basePath;
  }

  @GetMapping("${devpanel.base-path:/dev-panel}")
  public String page(HttpServletRequest request, HttpServletResponse response) {
    if (request.getRequestURI().endsWith("/")) {
      return "forward:" + basePath + "/index.html";
    }
    response.setStatus(HttpServletResponse.SC_FOUND);
    response.setHeader(HttpHeaders.LOCATION, basePath.substring(basePath.lastIndexOf('/') + 1)
        + "/");
    return null;
  }
}
