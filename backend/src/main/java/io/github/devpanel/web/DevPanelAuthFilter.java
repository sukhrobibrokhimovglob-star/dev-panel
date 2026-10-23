package io.github.devpanel.web;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.account.DevAccountRepository;
import io.github.devpanel.account.DevLoginAttempts;
import io.github.devpanel.support.DevPanelErrors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP Basic for {@code <base-path>/api/**} against {@code devpanel_account}, done by the panel
 * itself rather than through Spring Security: the application's security (adapter or filter-chain
 * style, or none at all) only has to ignore the panel's paths, which the auto-configuration
 * arranges. That is what lets the panel drop into any application unchanged.
 *
 * <p>Access: {@code POST /auth}, {@code GET /me} and {@code POST /me/password} for any logged-in
 * account (including one that still has to change its password); every other {@code GET} and the
 * read-only {@code POST /query} and {@code POST /query/excel} for any account; every other method
 * needs a {@code READ_WRITE} account. Which role may manage which account is decided in
 * {@code DevAccountService}.
 *
 * <p>No response ever carries {@code WWW-Authenticate}, or the browser would open its own login
 * dialog over the panel's form.
 */
public class DevPanelAuthFilter extends OncePerRequestFilter {

  public static final String ACCOUNT_ATTRIBUTE = DevPanelAuthFilter.class.getName() + ".ACCOUNT";

  private static final Set<String> OWN_ACCOUNT_PATHS = Set.of("/auth", "/me", "/me/password");
  /** POSTs only because the SQL goes in the body; they are read-only. */
  private static final Set<String> READ_ONLY_POSTS = Set.of("/query", "/query/excel");

  /** BCrypt costs ~100 ms; a verified header is trusted for this long while the hash is unchanged. */
  private static final Duration VERIFIED_FOR = Duration.ofMinutes(5);
  private static final int MAX_VERIFIED = 1000;

  private final String apiPath;
  private final DevAccountRepository repository;
  private final PasswordEncoder passwordEncoder;
  private final DevLoginAttempts loginAttempts;
  private final Map<String, Long> verified = new ConcurrentHashMap<>();

  public DevPanelAuthFilter(String apiPath, DevAccountRepository repository,
      PasswordEncoder passwordEncoder, DevLoginAttempts loginAttempts) {
    this.apiPath = apiPath;
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
    this.loginAttempts = loginAttempts;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return HttpMethod.OPTIONS.matches(request.getMethod());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String[] credentials = basicCredentials(request.getHeader("Authorization"));
    if (credentials == null) {
      DevPanelErrors.write(request, response, 401, "Login required");
      return;
    }
    String username = credentials[0].trim().toLowerCase(Locale.ROOT);

    Duration locked = loginAttempts.lockedFor(username);
    if (locked != null) {
      long minutes = Math.max(1, (locked.toSeconds() + 59) / 60);
      DevPanelErrors.write(request, response, 429,
          "Too many failed attempts; try again in " + minutes + " min");
      return;
    }

    Optional<DevAccount> found = repository.findByUsername(username);
    if (found.isEmpty() || !passwordMatches(credentials[1], found.get())) {
      loginAttempts.onFailure(username);
      DevPanelErrors.write(request, response, 401, "Invalid username or password");
      return;
    }
    DevAccount account = found.get();
    if (!account.enabled()) {
      DevPanelErrors.write(request, response, 401, "Account is disabled");
      return;
    }
    loginAttempts.onSuccess(username);

    String path = relativePath(request);
    boolean ownAccount = OWN_ACCOUNT_PATHS.contains(path);
    if (account.mustChangePassword() && !ownAccount) {
      DevPanelErrors.write(request, response, 403, "The temporary password must be changed first");
      return;
    }
    if (!ownAccount && needsWrite(request.getMethod(), path) && !account.canWrite()) {
      DevPanelErrors.write(request, response, 403, "This account has read-only access");
      return;
    }

    request.setAttribute(ACCOUNT_ATTRIBUTE, account);
    chain.doFilter(request, response);
  }

  private static boolean needsWrite(String method, String path) {
    if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
      return false;
    }
    return !(HttpMethod.POST.matches(method) && READ_ONLY_POSTS.contains(path));
  }

  private boolean passwordMatches(String password, DevAccount account) {
    String key = verifiedKey(account, password);
    Long until = verified.get(key);
    long now = System.currentTimeMillis();
    if (until != null && until > now) {
      return true;
    }
    if (!passwordEncoder.matches(password, account.passwordHash())) {
      return false;
    }
    if (verified.size() >= MAX_VERIFIED) {
      verified.clear();
    }
    verified.put(key, now + VERIFIED_FOR.toMillis());
    return true;
  }

  /** Includes the stored hash, so a password change or reset invalidates the entry by itself. */
  private static String verifiedKey(DevAccount account, String password) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(account.username().getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(password.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(account.passwordHash().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String relativePath(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.startsWith(apiPath) ? path.substring(apiPath.length()) : path;
  }

  private static String[] basicCredentials(String header) {
    if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return null;
    }
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
          StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
    int colon = decoded.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
  }
}
