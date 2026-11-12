package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewCapacityExecutionOwner;
import org.junit.jupiter.api.Test;

/**
 * Controlled authenticated-context negatives; not actual transport or physical composition proof.
 */
class CanonicalGameplayCapacityOriginRecoveryReceiverTest {
  private static final String NS = "origin-recovery-test";
  private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
  private final CanonicalGameplayOriginalNewCapacityExecutionOwner owner =
      mock(CanonicalGameplayOriginalNewCapacityExecutionOwner.class);
  private final CanonicalGameplayCapacityOriginRecoveryReceiver receiver =
      new CanonicalGameplayCapacityOriginRecoveryReceiver(
          owner, NS, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void missingPeerRefusesBeforePayloadOrMutation() {
    assertThatThrownBy(() -> receiver.receiveExact(null, null, new Metadata()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    verifyNoInteractions(owner);
  }

  @Test
  void wrongNamespaceRefusesBeforePayloadOrMutation() {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/other/sa/account-service").orElseThrow())
        .run(
            () -> {
              assertThatThrownBy(() -> receiver.receiveExact(null, null, new Metadata()))
                  .isInstanceOfSatisfying(
                      StatusRuntimeException.class,
                      e ->
                          org.assertj.core.api.Assertions.assertThat(e.getStatus().getCode())
                              .isEqualTo(Status.Code.PERMISSION_DENIED));
            });
    verifyNoInteractions(owner);
  }

  @Test
  void evenEmptyForwardedAuthorizationRefusesBeforePayload() {
    var headers = new Metadata();
    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "");
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + NS + "/sa/account-service")
                .orElseThrow())
        .run(
            () -> {
              assertThatThrownBy(() -> receiver.receiveExact(null, null, headers))
                  .isInstanceOfSatisfying(
                      StatusRuntimeException.class,
                      e ->
                          org.assertj.core.api.Assertions.assertThat(e.getStatus().getCode())
                              .isEqualTo(Status.Code.PERMISSION_DENIED));
            });
    verifyNoInteractions(owner);
  }
}
