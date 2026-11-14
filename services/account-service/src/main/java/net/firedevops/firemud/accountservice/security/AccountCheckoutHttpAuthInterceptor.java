package net.firedevops.firemud.accountservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceController;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceWebhookController;
import net.firedevops.firemud.accountservice.controller.AuthController;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.web.method.HandlerMethod;

/**
 * Exact handler ownership, never a public billing path pattern or a global authentication bypass.
 */
public final class AccountCheckoutHttpAuthInterceptor extends HttpJwtAuthInterceptor {
  private static final Set<String> OWNER_METHODS =
      Set.of("read", "reauthenticate", "confirm", "renew");
  private final AccountControlUiActorService actors;

  public AccountCheckoutHttpAuthInterceptor(
      JwtUtil legacy, HttpAuthProperties properties, AccountControlUiActorService actors) {
    super(legacy, properties);
    this.actors = Objects.requireNonNull(actors);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (handler instanceof HandlerMethod method) {
      if (method.getBeanType() == AuthController.class
          && "login".equals(method.getMethod().getName())) {
        // This isolated chain has one canonical login producer, reached through verified mTLS.
        response.setStatus(404);
        return false;
      }
      boolean owner =
          method.getBeanType() == AccountHostingFirstInvoiceController.class
              && OWNER_METHODS.contains(method.getMethod().getName());
      boolean webhook =
          method.getBeanType() == AccountHostingFirstInvoiceWebhookController.class
              && "receive".equals(method.getMethod().getName());
      if (owner || webhook) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        if (!request.isSecure()) {
          response.setStatus(401);
          return false;
        }
        if (webhook)
          return true; // The separate raw adapter requires the actual provider signature.
        String authorization = request.getHeader("Authorization");
        if (authorization == null
            || !authorization.startsWith("Bearer ")
            || authorization.length() <= 7
            || authorization.length() > 16_391) {
          response.setStatus(401);
          return false;
        }
        try {
          if (actors.authenticateAccount(authorization.substring(7)) == null) {
            response.setStatus(503);
            return false;
          }
          // Every owner mutation repeats currentness inside its actual owner transaction.
          return true;
        } catch (org.springframework.dao.DataAccessException
            | org.springframework.transaction.TransactionException
            | io.lettuce.core.RedisException
            | net.firedevops.firemud.accountservice.service.session.AccountControlUiRegistryReader
                .RegistryUnavailableException
            | net.firedevops.firemud.accountservice.service.session.AccountControlUiSignerOwner
                .SignerSourceUnavailableException
            | net.firedevops.firemud.common.security.AccountPublicJwksCache
                .SourceUnavailableException
            | net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier
                    .VerificationUnavailableException
                unavailable) {
          response.setStatus(503);
          return false;
        } catch (IllegalArgumentException
            | IllegalStateException
            | net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier
                    .VerificationException
                denied) {
          response.setStatus(401);
          return false;
        }
      }
    }
    return super.preHandle(request, response, handler);
  }
}
