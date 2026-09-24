package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class PublicationReadGuardTest {
  private static final String TRUSTED_NAMESPACE = "firemud";
  private static final GrpcPeerIdentity GAME_DESIGN_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/firemud/sa/game-design-service",
          TRUSTED_NAMESPACE,
          "game-design-service");

  private final PublicationReadGuard guard = new PublicationReadGuard(TRUSTED_NAMESPACE);

  @Test
  void allowsAllFourExactPublicationReadsWithPeerIdentityWithoutJwt() {
    withPeer(
        () -> {
          for (String method : PublicationReadGuard.PUBLICATION_READ_METHODS) {
            guard.requirePublicationRead(method);
          }
        });
  }

  @Test
  void deniesMissingPeerIdentityWithoutJwt() {
    assertThatThrownBy(
            () -> guard.requirePublicationRead(PublicationReadGuard.WORLD_MANAGEMENT_DIGEST_METHOD))
        .isInstanceOf(AdminAuthorizationException.class);
  }

  @Test
  void deniesWrongPeerWithoutJwt() {
    GrpcPeerIdentity wrongPeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/firemud/sa/world-management-service",
            TRUSTED_NAMESPACE,
            "world-management-service");
    withPeer(
        wrongPeer,
        () -> {
          assertThatThrownBy(
                  () -> guard.requirePublicationRead(PublicationReadGuard.GAME_LOGIC_DIGEST_METHOD))
              .isInstanceOf(AdminAuthorizationException.class);
        });
  }

  @Test
  void deniesGameDesignPeerFromWrongNamespace() {
    GrpcPeerIdentity wrongNamespacePeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/other/sa/game-design-service",
            "other",
            "game-design-service");
    withPeer(
        wrongNamespacePeer,
        () ->
            assertThatThrownBy(
                    () ->
                        guard.requirePublicationRead(
                            PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD))
                .isInstanceOf(AdminAuthorizationException.class));
  }

  @Test
  void leavesUnrelatedMethodsUntouched() {
    guard.requirePublicationRead("world_management.v1.WorldManagementService/Ping");
  }

  @Test
  void rejectsInvalidTrustedNamespace() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PublicationReadGuard("bad/ns"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void withPeer(Runnable action) {
    withPeer(GAME_DESIGN_PEER, action);
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }
}
