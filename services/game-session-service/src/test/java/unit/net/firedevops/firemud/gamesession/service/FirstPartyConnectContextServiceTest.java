package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.support.FirstPartyReceivingFixture;
import org.junit.jupiter.api.Test;

class FirstPartyConnectContextServiceTest {
  @Test
  void completeContextPreservesExactTupleMapsTimesAndSignedAbuseMetadata() {
    var fixture = new FirstPartyReceivingFixture();
    var context = fixture.context();
    assertThat(context.claims().accountId()).isEqualTo(FirstPartyReceivingFixture.ACCOUNT);
    assertThat(context.claims().authorityTuple()).isEqualTo(fixture.source().authorityTuple());
    assertThat(context.claims().membershipVersion())
        .isEqualTo(fixture.source().membershipVersion());
    assertThat(context.claims().connectTokenJti()).isEqualTo(fixture.source().connectTokenJti());
    assertThat(context.claims().replayAdmissionFence()).isEqualTo("5");
    assertThat(context.claims().issuedAtMillis()).isEqualTo(FirstPartyReceivingFixture.NOW);
    assertThat(context.claims().verifiedAtMillis()).isEqualTo(FirstPartyReceivingFixture.NOW);
    assertThat(context.claims().expiresAtMillis())
        .isEqualTo(FirstPartyReceivingFixture.NOW + 30_000L);
    assertThat(context.claims().canonicalClientAddress()).isEqualTo("203.0.113.8");
    assertThat(context.claims().gatewayRequestId()).isEqualTo("gateway-1");
    assertThat(context.gameSessionTenantId()).isEqualTo(22L);
    assertThat(context.runtimeGameInstanceId()).isEqualTo(41L);
    assertThat(context.toString()).doesNotContain(context.compactJws(), context.accountId());
  }

  @Test
  void rejectsOldHmacAndIncompleteTokensBeforeOwnerReads() {
    var fixture = new FirstPartyReceivingFixture();
    clearInvocations(fixture.routes);
    String hmac =
        new JwtUtil("old-connect-context-secret-32-bytes-long", 30_000L)
            .generateToken("77", java.util.Map.of());
    assertThat(fixture.service.parse(hmac)).isEmpty();
    assertThat(fixture.service.parse("incomplete.context")).isEmpty();
    verifyNoInteractions(fixture.routes);
  }

  @Test
  void missingDedicatedKeysAndMissingCurrentTargetDeny() {
    var fixture = new FirstPartyReceivingFixture();
    var unavailable =
        new FirstPartyConnectContextService(
            fixture.properties,
            kid -> null,
            fixture.routes,
            Clock.fixed(Instant.ofEpochMilli(FirstPartyReceivingFixture.NOW), ZoneOffset.UTC));
    assertThat(unavailable.parse(fixture.token())).isEmpty();
    when(fixture.routes.readCurrentTarget(FirstPartyReceivingFixture.TENANT))
        .thenThrow(new CanonicalPlayerRouteReadService.ReadUnavailableException("missing owner"));
    assertThat(fixture.service.parse(fixture.token())).isEmpty();
  }

  @Test
  void completeRetainedBytesMustBeReverifiedAndExpiredOrChangedTargetsDeny() throws Exception {
    var fixture = new FirstPartyReceivingFixture();
    var context = fixture.context();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(context);
    }
    FirstPartyConnectContext retained;
    try (ObjectInputStream input =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      retained = (FirstPartyConnectContext) input.readObject();
    }
    assertThatThrownBy(retained::claims).isInstanceOf(IllegalStateException.class);
    assertThat(fixture.service.requireRetained(retained).claims()).isEqualTo(context.claims());
    var expired =
        new FirstPartyConnectContextService(
            fixture.properties,
            fixture.keySource,
            fixture.routes,
            Clock.fixed(
                Instant.ofEpochMilli(FirstPartyReceivingFixture.NOW + 31_000L), ZoneOffset.UTC));
    assertThatThrownBy(() -> expired.requireRetained(retained))
        .isInstanceOf(IllegalStateException.class);
    when(fixture.routes.readCurrentTarget(FirstPartyReceivingFixture.TENANT)).thenReturn(null);
    assertThatThrownBy(() -> fixture.service.requireRetained(retained))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsEachChangedCanonicalScopeBeforeReturningAContext() {
    var fixture = new FirstPartyReceivingFixture();
    var original = fixture.target();
    for (int index = 0; index < 9; index++) {
      var changed = org.mockito.Mockito.spy(original);
      switch (index) {
        case 0 -> doReturn(java.util.UUID.randomUUID()).when(changed).canonicalTenantId();
        case 1 -> doReturn(java.util.UUID.randomUUID()).when(changed).realmId();
        case 2 -> doReturn("other-world").when(changed).worldSlug();
        case 3 -> doReturn("other-realm").when(changed).realmSlug();
        case 4 -> doReturn(java.util.UUID.randomUUID()).when(changed).playableStateNamespaceId();
        case 5 -> doReturn("ISOLATED").when(changed).playableStateScope();
        case 6 -> doReturn(java.util.UUID.randomUUID()).when(changed).canonicalGameInstanceId();
        case 7 -> doReturn(4L).when(changed).catalogRevision();
        case 8 -> doReturn(18L).when(changed).pointerVersion();
        default -> throw new AssertionError();
      }
      when(fixture.routes.readCurrentTarget(FirstPartyReceivingFixture.TENANT)).thenReturn(changed);
      assertThat(fixture.service.parse(fixture.token())).as("scope index %d", index).isEmpty();
    }
  }
}
