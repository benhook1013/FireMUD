package net.firedevops.firemud.gamesession.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.*;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextService;
import org.mockito.Mockito;

/** Controlled RSA and owner results; never producer or deployed runtime qualification. */
public final class FirstPartyReceivingFixture {
  public static final long NOW = 1_800_000_000_000L;
  public static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  public static final UUID TENANT = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  public static final UUID REALM = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  public static final UUID NAMESPACE = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  public static final UUID INSTANCE = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  public final KeyPair keys;
  public final CanonicalPlayerRouteReadService routes =
      Mockito.mock(CanonicalPlayerRouteReadService.class);
  public final FirstPartyConnectContextProperties properties =
      new FirstPartyConnectContextProperties();
  public final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  public final GatewayVerificationKeySource keySource;
  public final FirstPartyConnectContextService service;

  public FirstPartyReceivingFixture() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      keys = generator.generateKeyPair();
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    keySource = kid -> "gateway-test".equals(kid) ? (RSAPublicKey) keys.getPublic() : null;
    Mockito.when(routes.readCurrentTarget(TENANT)).thenReturn(target());
    service =
        new FirstPartyConnectContextService(
            properties,
            keySource,
            routes,
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
            meterRegistry);
  }

  public CanonicalPlayableTarget target() {
    return new CanonicalPlayableTarget(
        "firemud-test",
        "tenant",
        TENANT,
        "demo",
        "Demo",
        REALM,
        "production",
        "Production",
        22L,
        41L,
        NAMESPACE,
        "SHARED",
        INSTANCE,
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        7L,
        3L,
        17L,
        "d".repeat(64),
        1L,
        "initial-request",
        "a".repeat(64),
        net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind
            .NO_PRIOR_POINTER,
        null,
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString("22222222-2222-4222-8222-222222222222"),
        "sha256:" + "b".repeat(64),
        9L,
        "sha256:" + "c".repeat(64),
        CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        false,
        Instant.ofEpochMilli(NOW - 1000L),
        "PRESEEDED");
  }

  public GameplayConnectSource source() {
    return new GameplayConnectSource(
        ACCOUNT,
        TENANT,
        REALM,
        "demo",
        "production",
        NAMESPACE,
        "SHARED",
        INSTANCE,
        "17",
        "3",
        "scope-1",
        "request-1",
        new AuthorityTuple(
            "1",
            "1",
            Map.of(TENANT.toString(), "2"),
            Map.of(TENANT.toString(), "3"),
            List.of(),
            null,
            Map.of()),
        Map.of(TENANT.toString(), "4"),
        "a".repeat(64),
        "5",
        null,
        null,
        NOW / 1000L + 30L);
  }

  public String token() {
    return GatewayFirstPartyConnectContextEnvelope.sign(
        source(),
        new GatewayContextMetadata("203.0.113.8", "gateway-1", NOW, NOW, NOW + 30_000L),
        new GatewaySigningKey("gateway-test", keys.getPrivate()));
  }

  public FirstPartyConnectContext context() {
    return service.parse(token()).orElseThrow();
  }
}
