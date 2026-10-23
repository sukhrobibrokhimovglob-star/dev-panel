package io.github.devpanel.session;

import io.github.devpanel.account.DevAccount;
import io.github.devpanel.session.DevLoginService.LoginState;
import io.github.devpanel.session.DevSessions.Session;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The login steps, see {@link DevLoginService}. {@code POST /login} needs no session; the others
 * work on the session it started (the {@link Session} parameter).
 */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class DevLoginController {

  private final DevLoginService loginService;
  private final DevSessions sessions;

  public DevLoginController(DevLoginService loginService, DevSessions sessions) {
    this.loginService = loginService;
    this.sessions = sessions;
  }

  @PostMapping("/login")
  public ResponseEntity<LoginState> login(@RequestBody Map<String, String> body,
      HttpServletRequest request, HttpServletResponse response) {
    return noStore(loginService.login(body.get("username"), body.get("password"), request,
        response));
  }

  /** Where an unfinished login stands, e.g. after reloading the page; holds the QR during setup. */
  @GetMapping("/login/state")
  public ResponseEntity<LoginState> state(Session session) {
    return noStore(loginService.state(session));
  }

  @PostMapping("/login/password")
  public ResponseEntity<LoginState> changeTemporaryPassword(Session session,
      @RequestBody Map<String, String> body, HttpServletRequest request,
      HttpServletResponse response) {
    return noStore(loginService.changeTemporaryPassword(session, body.get("newPassword"), request,
        response));
  }

  @PostMapping("/login/code")
  public DevAccount verifyCode(Session session, @RequestBody Map<String, String> body,
      HttpServletRequest request, HttpServletResponse response) {
    return loginService.verifyCode(session, body.get("code"), request, response);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request, HttpServletResponse response) {
    sessions.end(request, response);
  }

  /** The setup secret must not be kept by any cache on the way. */
  private static ResponseEntity<LoginState> noStore(LoginState state) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state);
  }
}
