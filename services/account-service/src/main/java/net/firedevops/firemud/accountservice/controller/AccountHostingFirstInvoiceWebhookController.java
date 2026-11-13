package net.firedevops.firemud.accountservice.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.accountservice.service.session.AccountHostingFirstInvoicePaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Provider signature authentication over exact bounded bytes, independent of browser authority. */
@RestController
@Profile("isolated-checkout-receiving")
@ConditionalOnProperty(
    prefix = "firemud.account.checkout-receiving",
    name = "enabled",
    havingValue = "true")
public final class AccountHostingFirstInvoiceWebhookController {
  public static final int MAX_BODY_BYTES = 256 * 1024;
  private final AccountHostingFirstInvoicePaymentService payments;

  public AccountHostingFirstInvoiceWebhookController(
      AccountHostingFirstInvoicePaymentService payments) {
    this.payments = Objects.requireNonNull(payments);
  }

  @PostMapping(value = "/billing/hosting/webhook", consumes = "application/json")
  public ResponseEntity<AccountHostingFirstInvoicePaymentService.Completion> receive(
      HttpServletRequest request) throws IOException {
    if (!request.isSecure()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var signatures = request.getHeaders("Stripe-Signature");
    if (signatures == null || !signatures.hasMoreElements())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    String signature = signatures.nextElement();
    if (signatures.hasMoreElements() || signature.isBlank() || signature.length() > 4096)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    if (request.getContentLengthLong() > MAX_BODY_BYTES)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    try {
      if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES)
        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
      String raw =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      var completion = payments.completeSigned(raw, signature);
      // Acknowledge only exact terminal owner classifications. Stripe redelivery can retry
      // RECONCILING and unknown outcomes, but cannot resolve operator-owned reconciliation.
      String state = completion.state();
      boolean terminal =
          "COMMITTED".equals(state)
              || "IGNORED".equals(state)
              || "OPERATOR_RECONCILIATION_REQUIRED".equals(state);
      return ResponseEntity.status(terminal ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
          .cacheControl(CacheControl.noStore())
          .header("Pragma", "no-cache")
          .body(completion);
    } catch (CharacterCodingException
        | SignatureVerificationException
        | IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    } catch (StripeException unavailable) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
    } catch (IllegalStateException refused) {
      throw new ResponseStatusException(HttpStatus.CONFLICT);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }
}
