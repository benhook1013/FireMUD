package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/profiles")
public class ProfileController {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "AccountService is injected and not exposed")
  private final AccountService accountService;

  public ProfileController(AccountService accountService) {
    this.accountService = accountService;
  }

  @GetMapping("/{accountId}")
  public ResponseEntity<ApiResponse<ProfileDto>> getProfile(
      @PathVariable String accountId, @RequestParam String tenantId) {
    long parsedAccountId = AccountRequestReaders.requireAccountId(accountId);
    long parsedTenantId = AccountRequestReaders.requireTenantId(tenantId);
    SessionContext.requireAccountAccess(parsedTenantId, parsedAccountId);
    ProfileDto dto = accountService.getProfile(parsedTenantId, parsedAccountId);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PutMapping("/{accountId}")
  public ResponseEntity<ApiResponse<ProfileDto>> updateProfile(
      @PathVariable String accountId, @Valid @RequestBody UpdateProfileRequest request) {
    long parsedAccountId = AccountRequestReaders.requireAccountId(accountId);
    long parsedTenantId = AccountRequestReaders.requireTenantId(request.tenantId());
    request.presenceVisibilityPolicy().requireSelectableByAccountHolder();
    SessionContext.requireAccountAccess(parsedTenantId, parsedAccountId);
    ProfileDto dto =
        accountService.updateProfile(
            new UpdateProfileRequest(
                parsedTenantId,
                parsedAccountId,
                request.displayName(),
                request.bio(),
                request.presenceVisibilityPolicy()));
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
