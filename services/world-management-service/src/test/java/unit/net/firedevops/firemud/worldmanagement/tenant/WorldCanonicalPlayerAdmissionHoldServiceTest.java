package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerAdmissionHoldRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerAdmissionHoldService;
import org.junit.jupiter.api.Test;

class WorldCanonicalPlayerAdmissionHoldServiceTest {
  private final WorldCanonicalPlayerAdmissionHoldRepository repository =
      mock(WorldCanonicalPlayerAdmissionHoldRepository.class);
  private final WorldCanonicalPlayerAdmissionHoldService service =
      new WorldCanonicalPlayerAdmissionHoldService(repository, "firemud");

  @Test
  void unauthenticatedOrWrongPeerDeniesBeforeDecodeOrOwnerAccess() {
    assertThatThrownBy(() -> service.acquire(null, null, 0, 0))
        .isInstanceOf(SecurityException.class);
    for (String uri :
        java.util.List.of(
            "spiffe://firemud/ns/firemud/sa/game-session-service",
            "spiffe://firemud/ns/other/sa/account-service")) {
      peer(uri)
          .run(
              () ->
                  assertThatThrownBy(() -> service.read(null, null, 0, 0))
                      .isInstanceOf(SecurityException.class));
    }
    verifyNoInteractions(repository);
  }

  @Test
  void accountWorkloadWithAuthenticatedEndUserContextDeniesBeforeDecodeOrOwnerAccess() {
    SessionContext.setContext(
        WorldCanonicalPlayerAdmissionHoldTest.ACCOUNT, java.util.List.of(), java.util.Map.of());
    try {
      account()
          .run(
              () -> {
                assertThatThrownBy(() -> service.acquire(null, null, 0, 0))
                    .isInstanceOf(SecurityException.class);
                assertThatThrownBy(() -> service.read(null, null, 0, 0))
                    .isInstanceOf(SecurityException.class);
                assertThatThrownBy(service::release).isInstanceOf(SecurityException.class);
              });
      verifyNoInteractions(repository);
    } finally {
      SessionContext.clear();
    }
  }

  @Test
  void exactAccountProducerPassesOriginalLeaseWithoutChangingIdentity() {
    var lease = WorldCanonicalPlayerAdmissionHoldTest.lease();
    var request = new WorldCanonicalPlayerAdmissionHoldEvidence.Request(lease, 7L, 8L);
    var retained =
        new WorldCanonicalPlayerAdmissionHoldEvidence(
            UUID.randomUUID(),
            UUID.randomUUID(),
            request,
            WorldCanonicalPlayerAdmissionHoldTest.evidence());
    when(repository.acquire(request)).thenReturn(retained);
    when(repository.read(request)).thenReturn(Optional.of(retained));
    account()
        .run(
            () -> {
              assertThat(service.acquire(lease.canonicalJson(), lease.sha256(), 7L, 8L))
                  .isSameAs(retained);
              assertThat(service.read(lease.canonicalJson(), lease.sha256(), 7L, 8L))
                  .contains(retained);
            });
    verify(repository).acquire(request);
    verify(repository).read(request);
  }

  @Test
  void changedDigestOrNonCanonicalBytesDenyWithoutSql() {
    var lease = WorldCanonicalPlayerAdmissionHoldTest.lease();
    account()
        .run(
            () -> {
              assertThatThrownBy(
                      () -> service.acquire(lease.canonicalJson(), "b".repeat(64), 7L, 8L))
                  .isInstanceOf(IllegalArgumentException.class);
              assertThatThrownBy(
                      () -> service.acquire(" " + lease.canonicalJson(), lease.sha256(), 7L, 8L))
                  .isInstanceOf(IllegalArgumentException.class);
            });
    verifyNoInteractions(repository);
  }

  @Test
  void releaseHasNoAcceptingPathEvenForAuthenticatedAccount() {
    account()
        .run(
            () ->
                assertThatThrownBy(service::release)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Account terminal AND Game Session"));
    verifyNoInteractions(repository);
  }

  private static Context account() {
    return peer("spiffe://firemud/ns/firemud/sa/account-service");
  }

  private static Context peer(String uri) {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
  }
}
