package unit.net.firedevops.firemud.gamesession.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import net.firedevops.firemud.common.security.GatewayConnectContext;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.GatewayConnectContextSignature;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextService;
import net.firedevops.firemud.test.SelectedTargetConnectContextTestVectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirstPartyConnectContextServiceTest {
  private static final String KID = SelectedTargetConnectContextTestVectors.GATEWAY_KID;
  private static final String TENANT_ID = SelectedTargetConnectContextTestVectors.TENANT_ID;
  private static final Instant NOW =
      Instant.ofEpochSecond(SelectedTargetConnectContextTestVectors.NOW_EPOCH_SECONDS);

  private final ObjectMapper json = new ObjectMapper();
  private KeyPair keyPair;
  private Clock clock;
  private FirstPartyConnectContextService service;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    keyPair = generator.generateKeyPair();
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service = new FirstPartyConnectContextService();
  }

  @Test
  void runtimeParserFailsClosedWithoutPublishedGatewayVerificationKeys() {
    assertTrue(service.parse("untrusted-context").isEmpty());
  }

  @Test
  void verifiesRealEd25519ContextAndRetainsOriginalEnvelopeAndSelectedTarget() throws Exception {
    byte[] payload = json.writeValueAsBytes(validContext());
    String envelope = GatewayConnectContextSignature.sign(payload, KID, keyPair.getPrivate());

    GatewayConnectContext context =
        service.parseVerified(envelope, Map.of(KID, keyPair.getPublic()), clock).orElseThrow();

    Map<String, Object> source = SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims();
    assertEquals(envelope, context.signedEnvelope());
    assertArrayEquals(payload, context.signedPayload());
    assertEquals("game-session-service", context.text("recipient"));
    assertEquals(TENANT_ID, context.text("tenantId"));
    assertEquals(source.get("requestId"), context.text("connectRequestId"));
    assertEquals(source.get("jti"), context.text("connectTokenJti"));
    assertEquals(source.get("iat"), context.integer("issuedAt"));
    assertEquals("018f8f0a-4d9e-7c46-be37-8c1d0e9f7a5b", context.text("realmId"));
    assertEquals("018f8f0a-5eaf-7d57-9c48-9d2e1f0a8b6c", context.text("playableStateNamespaceId"));
    assertEquals("PLAYABLE_STATE_SCOPE_SHARED", context.text("playableStateScope"));
    assertEquals(
        SelectedTargetConnectContextTestVectors.LARGE_COUNTER,
        context.integer("replayAdmissionFence"));
  }

  @Test
  void rejectsContextWhenOnlyAnUnknownGatewayKeyIsProvided() throws Exception {
    String envelope =
        GatewayConnectContextSignature.sign(
            json.writeValueAsBytes(validContext()), KID, keyPair.getPrivate());

    assertTrue(
        service.parseVerified(envelope, Map.of("other-kid", keyPair.getPublic()), clock).isEmpty());
  }

  private static Map<String, Object> validContext() {
    return GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
        SelectedTargetConnectContextTestVectors.sourceConnectTokenClaims(),
        SelectedTargetConnectContextTestVectors.GATEWAY_VERIFIED_AT,
        "gateway-request-42");
  }
}
