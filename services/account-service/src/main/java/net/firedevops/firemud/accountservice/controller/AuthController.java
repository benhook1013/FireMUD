package net.firedevops.firemud.accountservice.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.List;
import net.firedevops.firemud.accountservice.dto.AccountIdRequest;
import net.firedevops.firemud.accountservice.dto.AuthenticationResult;
import net.firedevops.firemud.accountservice.dto.BootstrapCharacterDto;
import net.firedevops.firemud.accountservice.dto.BootstrapRealmDto;
import net.firedevops.firemud.accountservice.dto.BootstrapWorldDto;
import net.firedevops.firemud.accountservice.dto.CompletePasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenRequest;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResponse;
import net.firedevops.firemud.accountservice.dto.ConnectTokenResult;
import net.firedevops.firemud.accountservice.dto.LoginRequest;
import net.firedevops.firemud.accountservice.dto.PasswordResetRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapRequest;
import net.firedevops.firemud.accountservice.dto.PlayerBootstrapResult;
import net.firedevops.firemud.accountservice.dto.UsernameRecoveryRequest;
import net.firedevops.firemud.accountservice.dto.VerifyEmailRequest;
import net.firedevops.firemud.accountservice.service.AccountService;
import net.firedevops.firemud.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    AuthenticationResult auth = accountService.authenticate(request.username(), request.password());
    return ResponseEntity.ok(ApiResponse.success(auth));
  }

  @PostMapping("/player-bootstrap")
  public ResponseEntity<ApiResponse<PlayerBootstrapResult>> playerBootstrap(
      @Valid @RequestBody PlayerBootstrapRequest request) {
    PlayerBootstrapResult bootstrap =
        accountService.issuePlayerBootstrap(request.accountIdentifier(), request.secret());
    return ResponseEntity.ok(ApiResponse.success(bootstrap));
  }

  @GetMapping("/bootstrap/worlds")
  public ResponseEntity<ApiResponse<List<BootstrapWorldDto>>> listBootstrapWorlds(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    String bootstrapToken = extractBearerToken(authorization);
    return ResponseEntity.ok(
        ApiResponse.success(accountService.listBootstrapWorlds(bootstrapToken)));
  }

  @GetMapping("/bootstrap/worlds/{worldSlug}/realms")
  public ResponseEntity<ApiResponse<List<BootstrapRealmDto>>> listBootstrapRealms(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String worldSlug) {
    String bootstrapToken = extractBearerToken(authorization);
    return ResponseEntity.ok(
        ApiResponse.success(accountService.listBootstrapRealms(bootstrapToken, worldSlug)));
  }

  @GetMapping("/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters")
  public ResponseEntity<ApiResponse<List<BootstrapCharacterDto>>> listBootstrapCharacters(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String worldSlug,
      @PathVariable String realmSlug,
      @RequestParam String connectScopeId) {
    String bootstrapToken = extractBearerToken(authorization);
    return ResponseEntity.ok(
        ApiResponse.success(
            accountService.listBootstrapCharacters(
                bootstrapToken, worldSlug, realmSlug, connectScopeId)));
  }

  @PostMapping("/connect-token")
  public ResponseEntity<ApiResponse<ConnectTokenResponse>> connectToken(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @Valid @RequestBody ConnectTokenRequest request) {
    String bootstrapToken = extractBearerToken(authorization);
    ConnectTokenResult result = accountService.issueConnectToken(bootstrapToken, request);
    ResponseCookie cookie =
        ResponseCookie.from("Firemud-Connect-Token", result.connectToken())
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/ws/game")
            .maxAge(connectTokenMaxAgeSeconds(result))
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(ApiResponse.success(ConnectTokenResponse.from(result)));
  }

  private long connectTokenMaxAgeSeconds(ConnectTokenResult result) {
    try {
      java.time.Instant issuedAt = java.time.Instant.parse(result.issuedAt());
      java.time.Instant expiresAt = java.time.Instant.parse(result.expiresAt());
      return Math.max(0L, java.time.Duration.between(issuedAt, expiresAt).getSeconds());
    } catch (RuntimeException ignored) {
      return 30L;
    }
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
      @Valid @RequestBody AccountIdRequest request) {
    accountService.requestEmailVerification(request.accountId());
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

  private String extractBearerToken(String authorization) {
    if (authorization == null || authorization.isBlank()) {
      return null;
    }
    String prefix = "Bearer ";
    if (authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
      return authorization.substring(prefix.length()).trim();
    }
    return authorization.trim();
  }
}
