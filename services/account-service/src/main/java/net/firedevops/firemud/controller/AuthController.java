package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.AccountRefRequest;
import net.firedevops.firemud.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.dto.LoginRequest;
import net.firedevops.firemud.dto.PasswordResetRequest;
import net.firedevops.firemud.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.dto.VerifyEmailRequest;
import net.firedevops.firemud.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AccountService accountService;

  public AuthController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<Void>> login(@Valid @RequestBody LoginRequest request) {
    accountService.authenticate(
        request.tenantId(), request.username(), request.password(), request.otp());
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/request-password-reset")
  public ResponseEntity<ApiResponse<Void>> requestPasswordReset(
      @Valid @RequestBody PasswordResetRequest request) {
    accountService.requestPasswordReset(request);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/complete-password-reset")
  public ResponseEntity<ApiResponse<Void>> completePasswordReset(
      @Valid @RequestBody CompletePasswordResetRequest request) {
    accountService.completePasswordReset(request);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/request-email-verification")
  public ResponseEntity<ApiResponse<Void>> requestEmailVerification(
      @Valid @RequestBody AccountRefRequest request) {
    accountService.requestEmailVerification(request.tenantId(), request.accountId());
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/verify-email")
  public ResponseEntity<ApiResponse<Void>> verifyEmail(
      @Valid @RequestBody VerifyEmailRequest request) {
    accountService.verifyEmail(request);
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @PostMapping("/recover-username")
  public ResponseEntity<ApiResponse<Void>> recoverUsername(
      @Valid @RequestBody UsernameRecoveryRequest request) {
    accountService.sendUsernameReminder(request);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
