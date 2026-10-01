package net.firedevops.firemud.accountservice.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantResult;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runtime")
public class InternalRuntimeController {
  @PostMapping("/realm-access-grants")
  public ResponseEntity<ApiResponse<RealmAccessGrantResult>> grantRealmAccess(
      @Valid @RequestBody RealmAccessGrantRequest request) {
    SessionContext.requireGlobalPrivilegedRole();
    AccountRequestReaders.requireAccountId(request.accountId());
    AccountRequestReaders.requireTenantId(request.tenantId());
    throw grantMutationUnavailable();
  }

  @DeleteMapping("/realm-access-grants")
  public ResponseEntity<ApiResponse<Void>> revokeRealmAccess(
      @RequestParam("accountId") String accountId,
      @RequestParam("tenantId") String tenantId,
      @RequestParam("worldSlug") String worldSlug,
      @RequestParam("realmSlug") String realmSlug) {
    SessionContext.requireGlobalPrivilegedRole();
    AccountRequestReaders.requireAccountId(accountId);
    AccountRequestReaders.requireTenantId(tenantId);
    throw grantMutationUnavailable();
  }

  private static AuthenticationException grantMutationUnavailable() {
    return new AuthenticationException(
        "AUTH_UNAVAILABLE", "Lifecycle-qualified realm-grant mutations are unavailable");
  }
}
