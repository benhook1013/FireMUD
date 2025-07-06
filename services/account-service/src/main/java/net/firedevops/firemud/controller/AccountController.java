package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.AccountDto;
import net.firedevops.firemud.dto.CreateAccountRequest;
import net.firedevops.firemud.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {
  private final AccountService accountService;

  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<AccountDto>> createAccount(
      @Valid @RequestBody CreateAccountRequest request) {
    AccountDto dto = accountService.createAccount(request);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
