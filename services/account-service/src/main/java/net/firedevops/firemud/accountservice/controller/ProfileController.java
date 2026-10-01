package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    UUID parsedAccountUuid = AccountRequestReaders.requireAccountUuid(accountId);
    long parsedTenantId = AccountRequestReaders.requireTenantId(tenantId);
    requireProfileOwner(parsedAccountUuid);
    Long parsedAccountId = accountService.resolveAccountStorageId(parsedAccountUuid);
    ProfileDto dto = accountService.getProfile(parsedTenantId, parsedAccountId);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PutMapping("/{accountId}")
  public ResponseEntity<ApiResponse<ProfileDto>> updateProfile(
      @PathVariable String accountId, @Valid @RequestBody UpdateProfileRequest request) {
    UUID parsedAccountUuid = AccountRequestReaders.requireAccountUuid(accountId);
    long parsedTenantId = AccountRequestReaders.requireTenantId(request.tenantId());
    requireProfileOwner(parsedAccountUuid);
    UUID requestAccountUuid = AccountRequestReaders.requireAccountUuid(request.accountId());
    if (!parsedAccountUuid.equals(requestAccountUuid)) {
      throw new IllegalArgumentException("accountId must match the profile path");
    }
    if (request.presenceVisibilityPolicy() == null) {
      throw new IllegalArgumentException("presenceVisibilityPolicy must be provided");
    }
    request.presenceVisibilityPolicy().requireSelectableByAccountHolder();
    Long parsedAccountId = accountService.resolveAccountStorageId(parsedAccountUuid);
    ProfileDto dto =
        accountService.updateProfile(
            parsedAccountId,
            new UpdateProfileRequest(
                parsedTenantId,
                requestAccountUuid.toString(),
                request.displayName(),
                request.bio(),
                request.presenceVisibilityPolicy()));
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  private static void requireProfileOwner(UUID accountUuid) {
    if (!accountUuid.toString().equals(SessionContext.getAccountId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Profile access required");
    }
  }
}
