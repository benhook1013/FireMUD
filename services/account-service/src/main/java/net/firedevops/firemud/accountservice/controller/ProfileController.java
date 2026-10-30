package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.UUID;
import net.firedevops.firemud.accountservice.AccountUuidText;
import net.firedevops.firemud.accountservice.dto.ProfileDto;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRequest;
import net.firedevops.firemud.accountservice.dto.UpdateProfileRestRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.dao.DataAccessException;
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
    UUID parsedAccountUuid = requireAccountUuid(accountId);
    long parsedTenantId = AccountRequestReaders.requireTenantId(tenantId);
    requireProfileOwner(parsedAccountUuid);
    Long parsedAccountId = resolveAccountStorageId(parsedAccountUuid);
    ProfileDto dto = accountService.getProfile(parsedTenantId, parsedAccountId);
    requireProfileResult(dto, parsedAccountUuid, parsedTenantId);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @PutMapping("/{accountId}")
  public ResponseEntity<ApiResponse<ProfileDto>> updateProfile(
      @PathVariable String accountId, @Valid @RequestBody UpdateProfileRestRequest request) {
    UUID parsedAccountUuid = requireAccountUuid(accountId);
    UUID bodyAccountUuid = requireAccountUuid(request.accountId());
    if (!parsedAccountUuid.equals(bodyAccountUuid)) {
      throw new IllegalArgumentException("accountId must match the path selector");
    }
    long parsedTenantId = AccountRequestReaders.requireTenantId(request.tenantId());
    requireProfileOwner(parsedAccountUuid);
    resolveAccountStorageId(parsedAccountUuid);
    if (request.presenceVisibilityPolicy() == null) {
      throw new IllegalArgumentException("presenceVisibilityPolicy must be provided");
    }
    request.presenceVisibilityPolicy().requireSelectableByAccountHolder();
    ProfileDto dto =
        accountService.updateProfile(
            new UpdateProfileRequest(
                parsedTenantId,
                parsedAccountUuid.toString(),
                request.displayName(),
                request.bio(),
                request.presenceVisibilityPolicy()));
    requireProfileResult(dto, parsedAccountUuid, parsedTenantId);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  private long resolveAccountStorageId(UUID accountUuid) {
    try {
      Long storageId = accountService.resolveAccountStorageId(accountUuid);
      if (storageId == null || storageId <= 0L) {
        throw new IllegalStateException("Account UUID resolved to an invalid storage owner");
      }
      return storageId;
    } catch (IllegalStateException | DataAccessException ex) {
      throw new AuthenticationException("AUTH_UNAVAILABLE", "Account authority is unavailable", ex);
    }
  }

  private static UUID requireAccountUuid(String accountId) {
    UUID parsed = AccountUuidText.parseOrNull(accountId);
    if (parsed == null) {
      throw new IllegalArgumentException("accountId must be a canonical non-nil Account UUID");
    }
    return parsed;
  }

  private static void requireProfileResult(ProfileDto dto, UUID accountUuid, long tenantId) {
    if (dto == null
        || !accountUuid.toString().equals(dto.accountId())
        || !Long.valueOf(tenantId).equals(dto.tenantId())) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Profile authority unavailable");
    }
  }

  private static void requireProfileOwner(UUID accountUuid) {
    if (!accountUuid.toString().equals(SessionContext.getAccountId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Profile access required");
    }
  }
}
