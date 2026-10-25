package io.github.devpanel.account;

import io.github.devpanel.account.DevAccountRequests.Create;
import io.github.devpanel.account.DevAccountRequests.Credentials;
import io.github.devpanel.account.DevAccountRequests.PasswordChange;
import io.github.devpanel.account.DevAccountRequests.Update;
import io.github.devpanel.audit.DevActionLog;
import io.github.devpanel.session.DevSessions;
import io.github.devpanel.session.DevSessions.Session;
import io.github.devpanel.session.DevSessions.SessionView;
import io.github.devpanel.support.DevPanelException;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in account and account management. Who may do what is enforced in {@link DevAccountService};
 * the {@link DevAccount} parameter is the logged-in account.
 */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class DevAccountController {

  private final DevAccountService accountService;
  private final DevSessions sessions;
  private final DevActionLog actions;

  public DevAccountController(DevAccountService accountService, DevSessions sessions,
      DevActionLog actions) {
    this.accountService = accountService;
    this.sessions = sessions;
    this.actions = actions;
  }

  @GetMapping("/me")
  public DevAccount me(DevAccount actor) {
    return actor;
  }

  @PostMapping("/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(DevAccount actor, @RequestBody PasswordChange request) {
    accountService.changePassword(actor, request);
  }

  /** Where this account is logged in, so a stranger's session stands out and can be ended. */
  @GetMapping("/me/sessions")
  public List<SessionView> sessions(DevAccount actor, Session session) {
    return sessions.list(actor.id(), session.tokenHash());
  }

  @DeleteMapping("/me/sessions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void endSession(DevAccount actor, @PathVariable String id) {
    if (!sessions.endOne(actor.id(), id)) {
      throw DevPanelException.notFound("No such session");
    }
  }

  @PostMapping("/me/sessions/end-others")
  public Map<String, Integer> endOtherSessions(DevAccount actor, Session session) {
    return Map.of("ended", sessions.endOthers(actor.id(), session.tokenHash()));
  }

  @GetMapping("/accounts")
  public List<DevAccount> list(DevAccount actor) {
    return accountService.list(actor);
  }

  @PostMapping("/accounts")
  public ResponseEntity<Credentials> create(DevAccount actor, @RequestBody Create request) {
    Credentials created = accountService.create(actor, request);
    actions.record(actor, "account.create", request.username(),
        "role " + request.role() + ", access " + request.access());
    return credentials(HttpStatus.CREATED, created);
  }

  @PatchMapping("/accounts/{id}")
  public DevAccount update(DevAccount actor, @PathVariable long id,
      @RequestBody(required = false) Update request) {
    DevAccount updated = accountService.update(actor, id, request);
    actions.record(actor, "account.update", updated.username(), request == null ? null
        : "role " + request.role() + ", access " + request.access() + ", enabled "
            + request.enabled());
    return updated;
  }

  @PostMapping("/accounts/{id}/reset-password")
  public ResponseEntity<Credentials> resetPassword(DevAccount actor, @PathVariable long id) {
    Credentials reset = accountService.resetPassword(actor, id);
    actions.record(actor, "account.reset-password", reset.account().username(), null);
    return credentials(HttpStatus.OK, reset);
  }

  @PostMapping("/accounts/{id}/reset-two-factor")
  public DevAccount resetTwoFactor(DevAccount actor, @PathVariable long id) {
    DevAccount reset = accountService.resetTwoFactor(actor, id);
    actions.record(actor, "account.reset-two-factor", reset.username(), null);
    return reset;
  }

  /** A temporary password must not be stored by any cache on the way. */
  private static ResponseEntity<Credentials> credentials(HttpStatus status, Credentials body) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }
}
