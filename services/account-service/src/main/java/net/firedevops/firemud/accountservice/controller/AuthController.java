package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import net.firedevops.firemud.accountservice.dto.AccountRefRequest;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.LoginRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AccountService accountService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "AccountService is injected and not exposed")
  public AuthController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<AuthenticationResult>> login(
      @Valid @RequestBody LoginRequest request) {
    AuthenticationResult auth =
        accountService.authenticate(
            request.tenantId(), request.username(), request.password(), request.otp());
    return ResponseEntity.ok(ApiResponse.success(auth));
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
