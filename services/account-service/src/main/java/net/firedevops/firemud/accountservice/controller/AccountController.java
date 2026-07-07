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
import net.firedevops.firemud.common.security.RequestIdValidation;
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
      @PathVariable String accountId) {
    long parsedAccountId = requireAccountId(accountId);
    requireCurrentAccountOrGlobalPrivilegedRole(parsedAccountId);
    AccountDataExportDto data = accountService.exportAccountData(parsedAccountId);
    return ResponseEntity.ok(ApiResponse.success(data));
  }

  @GetMapping("/{accountId}/tenant-export")
  public ResponseEntity<ApiResponse<TenantDataExportDto>> exportTenantData(
      @PathVariable String accountId, @RequestParam String tenantId) {
    long parsedAccountId = requireAccountId(accountId);
    long parsedTenantId = requireTenantId(tenantId);
    SessionContext.requireAccountAccess(parsedTenantId, parsedAccountId);
    TenantDataExportDto data = accountService.exportTenantData(parsedTenantId, parsedAccountId);
    return ResponseEntity.ok(ApiResponse.success(data));
  }

  @DeleteMapping("/{accountId}")
  public ResponseEntity<ApiResponse<Void>> deleteAccount(@PathVariable String accountId) {
    long parsedAccountId = requireAccountId(accountId);
    requireCurrentAccountOrGlobalPrivilegedRole(parsedAccountId);
    accountService.deleteAccount(parsedAccountId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/{accountId}/external")
  public ResponseEntity<ApiResponse<Void>> linkExternalAccount(
      @PathVariable String accountId, @Valid @RequestBody LinkExternalAccountRequest request) {
    long parsedAccountId = requireAccountId(accountId);
    SessionContext.requireAccountAccess(request.tenantId(), parsedAccountId);
    accountService.linkExternalAccount(
        new LinkExternalAccountRequest(
            request.tenantId(), parsedAccountId, request.provider(), request.externalId()));
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  private void requireCurrentAccountOrGlobalPrivilegedRole(Long accountId) {
    if (SessionContext.isCurrentAccount(accountId) || SessionContext.hasGlobalPrivilegedRole()) {
      return;
    }
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.FORBIDDEN, "Account access required");
  }

  private long requireAccountId(String accountId) {
    return RequestIdValidation.requirePositiveLong(accountId, "accountId");
  }

  private long requireTenantId(String tenantId) {
    return RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
  }
}
