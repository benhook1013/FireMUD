package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipRequest;
import net.firedevops.firemud.accountservice.dto.PublicProductionMembershipResult;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantRequest;
import net.firedevops.firemud.accountservice.dto.RealmAccessGrantResult;
import net.firedevops.firemud.accountservice.service.AccountService;
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
  private final AccountService accountService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "AccountService is injected and not exposed")
  public InternalRuntimeController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping("/public-production-membership")
  public ResponseEntity<ApiResponse<PublicProductionMembershipResult>>
      ensurePublicProductionMembership(
          @Valid @RequestBody PublicProductionMembershipRequest request) {
    SessionContext.requireGlobalPrivilegedRole();
    return ResponseEntity.ok(
        ApiResponse.success(
            accountService.ensurePublicProductionPlayerMembership(
                request.accountId(),
                request.tenantId(),
                request.worldSlug(),
                request.realmSlug(),
                request.requestId())));
  }

  @PostMapping("/realm-access-grants")
  public ResponseEntity<ApiResponse<RealmAccessGrantResult>> grantRealmAccess(
      @Valid @RequestBody RealmAccessGrantRequest request) {
    SessionContext.requireGlobalPrivilegedRole();
    return ResponseEntity.ok(ApiResponse.success(accountService.grantRealmAccess(request)));
  }

  @DeleteMapping("/realm-access-grants")
  public ResponseEntity<ApiResponse<Void>> revokeRealmAccess(
      @RequestParam("accountId") Long accountId,
      @RequestParam("tenantId") Long tenantId,
      @RequestParam("worldSlug") String worldSlug,
      @RequestParam("realmSlug") String realmSlug) {
    SessionContext.requireGlobalPrivilegedRole();
    accountService.revokeRealmAccess(accountId, tenantId, worldSlug, realmSlug);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
