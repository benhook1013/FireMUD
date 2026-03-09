package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.RequireAdminRole;
import net.firedevops.firemud.accountservice.dto.AccountDataExportDto;
import net.firedevops.firemud.accountservice.dto.AccountDto;
import net.firedevops.firemud.accountservice.dto.CreateAccountRequest;
import net.firedevops.firemud.accountservice.dto.LinkExternalAccountRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
      @PathVariable Long accountId, Long tenantId) {
    AccountDataExportDto data = accountService.exportAccountData(tenantId, accountId);
    return ResponseEntity.ok(ApiResponse.success(data));
  }

  @DeleteMapping("/{accountId}")
  @RequireAdminRole
  public ResponseEntity<ApiResponse<Void>> deleteAccount(
      @PathVariable Long accountId, Long tenantId) {
    accountService.deleteAccount(tenantId, accountId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/{accountId}/external")
  public ResponseEntity<ApiResponse<Void>> linkExternalAccount(
      @PathVariable Long accountId, @Valid @RequestBody LinkExternalAccountRequest request) {
    accountService.linkExternalAccount(
        new LinkExternalAccountRequest(
            request.tenantId(), accountId, request.provider(), request.externalId()));
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
