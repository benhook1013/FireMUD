package net.firedevops.firemud.gamesession.service;

import java.security.PublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.security.GatewayConnectContext;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import org.springframework.stereotype.Component;

/** Receives complete Gateway-signed contexts; no Account-JWT or shared-secret fallback exists. */
@Component
public class FirstPartyConnectContextService {

  /**
   * Validates a context against the explicitly supplied Gateway public-key ring.
   *
   * <p>The Gateway key-publication source is not yet wired into the runtime. Callers must not
   * replace the empty runtime key set with an Account JWT key or a test/default key.
   */
  public Optional<GatewayConnectContext> parseVerified(
      String signedEnvelope,
      Map<String, ? extends PublicKey> gatewayVerificationKeys,
      Clock clock) {
    try {
      return Optional.of(
          GatewayConnectContextCodec.verifyAndDecode(
              signedEnvelope, gatewayVerificationKeys, clock));
    } catch (IllegalArgumentException ex) {
      return Optional.empty();
    }
  }

  /**
   * Runtime admission remains closed until the independently published Gateway key set is wired.
   * The legacy numeric Game Session carrier cannot be populated from canonical UUID target IDs.
   */
  public Optional<FirstPartyConnectContext> parse(String signedEnvelope) {
    return Optional.empty();
  }
}
