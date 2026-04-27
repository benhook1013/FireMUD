package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest;
import net.firedevops.firemud.accountservice.dto.TenantDataExportDto;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {
  private final AccountService accountService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "AccountService is injected and not exposed")
  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<AccountDto>> createAccount(
      @Valid @RequestBody CreateAccountRequest request) {
    AccountDto dto = accountService.createAccount(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @GetMapping("/{accountId}/export")
  public ResponseEntity<ApiResponse<AccountDataExportDto>> exportAccount(
      @PathVariable Long accountId) {
    requireCurrentAccountOrGlobalPrivilegedRole(accountId);
    AccountDataExportDto data = accountService.exportAccountData(accountId);
    return ResponseEntity.ok(ApiResponse.success(data));
  }

  @GetMapping("/{accountId}/tenant-export")
  public ResponseEntity<ApiResponse<TenantDataExportDto>> exportTenantData(
      @PathVariable Long accountId, @RequestParam Long tenantId) {
    SessionContext.requireAccountAccess(tenantId, accountId);
    TenantDataExportDto data = accountService.exportTenantData(tenantId, accountId);
    return ResponseEntity.ok(ApiResponse.success(data));
  }

  @DeleteMapping("/{accountId}")
  public ResponseEntity<ApiResponse<Void>> deleteAccount(@PathVariable Long accountId) {
    requireCurrentAccountOrGlobalPrivilegedRole(accountId);
    accountService.deleteAccount(accountId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/{accountId}/external")
  public ResponseEntity<ApiResponse<Void>> linkExternalAccount(
      @PathVariable Long accountId, @Valid @RequestBody LinkExternalAccountRequest request) {
    SessionContext.requireAccountAccess(request.tenantId(), accountId);
    accountService.linkExternalAccount(
        new LinkExternalAccountRequest(
            request.tenantId(), accountId, request.provider(), request.externalId()));
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  private void requireCurrentAccountOrGlobalPrivilegedRole(Long accountId) {
    if (SessionContext.isCurrentAccount(accountId) || SessionContext.hasGlobalPrivilegedRole()) {
      return;
    }
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.FORBIDDEN, "Account access required");
  }
}
