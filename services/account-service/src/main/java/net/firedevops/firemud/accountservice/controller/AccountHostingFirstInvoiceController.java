package net.firedevops.firemud.accountservice.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationEvidence.BillingAuthorizationPurpose;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/** Deliberately NOT a registered controller. Retention/custody and route activation remain held. */
@RequestMapping("/billing/hosting/subscriptions/{subscriptionId}/first-invoice")
public final class AccountHostingFirstInvoiceController {
  private final AccountHostingFirstInvoicePaymentService payments;

  public AccountHostingFirstInvoiceController(AccountHostingFirstInvoicePaymentService payments) {
    this.payments = Objects.requireNonNull(payments);
  }

  @GetMapping
  public ResponseEntity<AccountHostingFirstInvoicePaymentService.OwnerView> read(
      HttpServletRequest transport,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable UUID subscriptionId) {
    return noStore(payments.describeOwner(bearer(transport, authorization), subscriptionId));
  }

  @PostMapping("/reauthenticate")
  public ResponseEntity<ReauthenticationResponse> reauthenticate(
      HttpServletRequest transport,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable UUID subscriptionId,
      @RequestBody ReauthenticationRequest request) {
    String token = bearer(transport, authorization);
    if (request.billingBinding() == null) {
      throw new IllegalStateException("Exact first-invoice billing binding required");
    }
    var binding = request.billingBinding();
    return noStore(
        new ReauthenticationResponse(
            payments.reauthenticate(
                token,
                subscriptionId,
                request.requestId(),
                request.confirmationRequestId(),
                binding.originalRequestId(),
                binding.expectedRowVersion(),
                binding.expectedAuthorizationVersion(),
                binding.acceptedTupleDigest(),
                binding.invoiceExpiresAt(),
                binding.authorizationPurpose(),
                request.email(),
                request.secret())));
  }

  @PostMapping("/confirm")
  public ResponseEntity<ConfirmationResponse> confirm(
      HttpServletRequest transport,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable UUID subscriptionId,
      @RequestBody ConfirmationRequest request)
      throws StripeException {
    String token = bearer(transport, authorization);
    if (!request.acceptCharge())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Explicit exact charge consent required");
    var handoff =
        payments.confirm(
            token,
            subscriptionId,
            request.requestId(),
            request.expectedRowVersion(),
            request.reauthenticationEvidenceId(),
            request.acceptedTupleDigest());
    return noStore(new ConfirmationResponse(handoff.status(), handoff.clientSecret()));
  }

  /** Fresh explicit owner consent on the unchanged original payment operation. */
  @PostMapping("/renew")
  public ResponseEntity<ConfirmationResponse> renew(
      HttpServletRequest transport,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable UUID subscriptionId,
      @RequestBody RenewalRequest request)
      throws StripeException {
    String token = bearer(transport, authorization);
    if (!request.acceptCharge())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Explicit exact charge consent required");
    var handoff =
        payments.renew(
            token,
            subscriptionId,
            request.requestId(),
            request.authorizationRequestId(),
            request.expectedRowVersion(),
            request.expectedAuthorizationVersion(),
            request.reauthenticationEvidenceId(),
            request.acceptedTupleDigest());
    return noStore(new ConfirmationResponse(handoff.status(), handoff.clientSecret()));
  }

  /** Server-only intake adapter; not an owner browser endpoint and not registered here. */
  public AccountHostingFirstInvoicePaymentService.Completion signedCompletion(
      String payload, String signature) throws SignatureVerificationException, StripeException {
    return payments.completeSigned(payload, signature);
  }

  @ExceptionHandler(AccountAsymmetricJwtVerifier.VerificationException.class)
  public ResponseEntity<ErrorResponse> authenticationRefused() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse("FIRST_INVOICE_AUTHENTICATION_REQUIRED"));
  }

  @ExceptionHandler(StripeException.class)
  public ResponseEntity<ErrorResponse> providerUncertain() {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse("FIRST_INVOICE_PROVIDER_UNCERTAIN"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> invalidRequest() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse("FIRST_INVOICE_INVALID_REQUEST"));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorResponse> evidenceRefused() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .cacheControl(CacheControl.noStore())
        .body(new ErrorResponse("FIRST_INVOICE_EVIDENCE_REFUSED"));
  }

  public record ErrorResponse(String code) {}

  private static String bearer(HttpServletRequest transport, String authorization) {
    if (transport == null
        || !transport.isSecure()
        || authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() <= 7
        || authorization.substring(7).isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Protected HTTPS control-ui authentication required");
    }
    return authorization.substring(7);
  }

  private static <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  public record ReauthenticationRequest(
      UUID requestId,
      UUID confirmationRequestId,
      BillingReauthenticationBinding billingBinding,
      String email,
      String secret) {
    public ReauthenticationRequest(
        UUID requestId, UUID confirmationRequestId, String email, String secret) {
      this(requestId, confirmationRequestId, null, email, secret);
    }

    @Override
    public String toString() {
      return "HostingBillingReauthenticationRequest[redacted]";
    }
  }

  public record BillingReauthenticationBinding(
      UUID originalRequestId,
      long expectedRowVersion,
      long expectedAuthorizationVersion,
      String acceptedTupleDigest,
      Instant invoiceExpiresAt,
      BillingAuthorizationPurpose authorizationPurpose) {}

  public record ReauthenticationResponse(UUID evidenceId) {}

  public record ConfirmationRequest(
      UUID requestId,
      long expectedRowVersion,
      UUID reauthenticationEvidenceId,
      String acceptedTupleDigest,
      boolean acceptCharge) {}

  public record ConfirmationResponse(String status, String clientSecret) {
    @Override
    public String toString() {
      return "HostingFirstInvoiceConfirmationResponse[redacted]";
    }
  }

  public record RenewalRequest(
      UUID requestId,
      UUID authorizationRequestId,
      long expectedRowVersion,
      long expectedAuthorizationVersion,
      UUID reauthenticationEvidenceId,
      String acceptedTupleDigest,
      boolean acceptCharge) {}
}
