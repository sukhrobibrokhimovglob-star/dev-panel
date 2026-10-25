package io.github.devpanel.web;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.account.DevAccountRepository;
import io.github.devpanel.session.DevSessionStage;
import io.github.devpanel.session.DevSessions;
import io.github.devpanel.session.DevSessions.Session;
import io.github.devpanel.support.DevPanelErrors;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code <base-path>/api/**} with the panel's own session cookie, done by the panel itself
 * rather than through Spring Security: the application's security (adapter or filter-chain style,
 * or none at all) only has to ignore the panel's paths, which the auto-configuration arranges.
 *
 * <p>Access:
 * <ul>
 *   <li>{@code POST /login} needs no session;</li>
 *   <li>a login that is not finished (see {@link DevSessionStage}) reaches only the remaining
 *       login steps and {@code /logout};</li>
 *   <li>a finished login: every {@code GET} and the read-only {@code POST /query},
 *       {@code POST /query/excel} and {@code POST /database/explain} for any account, every
 *       other method only for a
 *       {@code READ_WRITE} account (who may manage which account is decided in
 *       {@code DevAccountService}).</li>
 * </ul>
 *
 * <p>Every request other than {@code GET} must carry the {@value #CSRF_HEADER} header. A plain
 * form or link on another site cannot add one, which together with the {@code SameSite=Strict}
 * cookie rules out cross-site request forgery.
 */
public class DevPanelAuthFilter extends OncePerRequestFilter {

  public static final String ACCOUNT_ATTRIBUTE = DevPanelAuthFilter.class.getName() + ".ACCOUNT";
  public static final String SESSION_ATTRIBUTE = DevPanelAuthFilter.class.getName() + ".SESSION";
  public static final String CSRF_HEADER = "X-DevPanel-Request";

  private static final String LOGIN = "/login";
  private static final Set<String> LOGIN_STEPS =
      Set.of("/login/state", "/login/password", "/login/code", "/logout");
  /** Own account; allowed even for a read-only account. */
  private static final Set<String> OWN_ACCOUNT_PATHS = Set.of("/me/password", "/logout");
  /** POSTs only because the SQL goes in the body; they are read-only. */
  private static final Set<String> READ_ONLY_POSTS = Set.of("/query", "/query/excel",
      "/database/explain");

  private final String apiPath;
  private final DevAccountRepository accounts;
  private final DevSessions sessions;

  public DevPanelAuthFilter(String apiPath, DevAccountRepository accounts, DevSessions sessions) {
    this.apiPath = apiPath;
    this.accounts = accounts;
    this.sessions = sessions;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return HttpMethod.OPTIONS.matches(request.getMethod());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String method = request.getMethod();
    boolean reading = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
    if (!reading && request.getHeader(CSRF_HEADER) == null) {
      DevPanelErrors.write(request, response, 403, "Missing " + CSRF_HEADER + " header");
      return;
    }

    String path = relativePath(request);
    if (HttpMethod.POST.matches(method) && LOGIN.equals(path)) {
      chain.doFilter(request, response);
      return;
    }

    Optional<Session> found = sessions.current(request);
    if (found.isEmpty()) {
      DevPanelErrors.write(request, response, 401, "Login required");
      return;
    }
    Session session = found.get();
    Optional<DevAccount> account = accounts.findById(session.accountId())
        .filter(DevAccount::enabled);
    if (account.isEmpty()) {
      sessions.end(request, response);
      DevPanelErrors.write(request, response, 401, "Login required");
      return;
    }

    if (session.stage() != DevSessionStage.ACTIVE && !LOGIN_STEPS.contains(path)) {
      DevPanelErrors.write(request, response, 401, "The login is not finished");
      return;
    }
    if (!reading && !OWN_ACCOUNT_PATHS.contains(path) && !READ_ONLY_POSTS.contains(path)
        && !LOGIN_STEPS.contains(path) && !account.get().canWrite()) {
      DevPanelErrors.write(request, response, 403, "This account has read-only access");
      return;
    }

    request.setAttribute(SESSION_ATTRIBUTE, session);
    request.setAttribute(ACCOUNT_ATTRIBUTE, account.get());
    chain.doFilter(request, response);
  }

  private String relativePath(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith(apiPath) ? path.substring(apiPath.length()) : path;
  }
}
