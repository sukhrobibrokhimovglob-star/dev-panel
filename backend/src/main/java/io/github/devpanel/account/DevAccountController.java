package io.github.devpanel.account;

import io.github.devpanel.account.DevAccountRequests.Create;
import io.github.devpanel.account.DevAccountRequests.Credentials;
import io.github.devpanel.account.DevAccountRequests.PasswordChange;
import io.github.devpanel.account.DevAccountRequests.Update;
import io.github.devpanel.web.DevPanelApi;
import io.github.devpanel.web.DevPanelPaths;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login check and account management. Who may do what is enforced in {@link DevAccountService};
 * the {@link DevAccount} parameter is the logged-in account.
 */
@DevPanelApi
@RestController
@RequestMapping(DevPanelPaths.API)
public class DevAccountController {

  private final DevAccountService accountService;

  public DevAccountController(DevAccountService accountService) {
    this.accountService = accountService;
  }

  /**
   * Login check of the panel's login form: 204 when the Basic credentials are valid, 401
   * otherwise. It holds no state; the client sends the same header on every later request.
   */
  @PostMapping("/auth")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void auth(DevAccount actor) {
  }

  @GetMapping("/me")
  public DevAccount me(DevAccount actor) {
    return actor;
  }

  /** Also the only thing an account can do until it has replaced its temporary password. */
  @PostMapping("/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(DevAccount actor, @RequestBody PasswordChange request) {
    accountService.changePassword(actor, request);
  }

  @GetMapping("/accounts")
  public List<DevAccount> list(DevAccount actor) {
    return accountService.list(actor);
  }

  @PostMapping("/accounts")
  public ResponseEntity<Credentials> create(DevAccount actor, @RequestBody Create request) {
    return credentials(HttpStatus.CREATED, accountService.create(actor, request));
  }

  @PatchMapping("/accounts/{id}")
  public DevAccount update(DevAccount actor, @PathVariable long id,
      @RequestBody(required = false) Update request) {
    return accountService.update(actor, id, request);
  }

  @PostMapping("/accounts/{id}/reset-password")
  public ResponseEntity<Credentials> resetPassword(DevAccount actor, @PathVariable long id) {
    return credentials(HttpStatus.OK, accountService.resetPassword(actor, id));
  }

  /** A temporary password must not be stored by any cache on the way. */
  private static ResponseEntity<Credentials> credentials(HttpStatus status, Credentials body) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }
}
