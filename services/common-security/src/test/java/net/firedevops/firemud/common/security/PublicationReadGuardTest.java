package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublicationReadGuardTest {
  private static final String TRUSTED_NAMESPACE = "firemud";
  private static final GrpcPeerIdentity GAME_DESIGN_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/firemud/sa/game-design-service",
          TRUSTED_NAMESPACE,
          "game-design-service");

  private final PublicationReadGuard guard = new PublicationReadGuard(TRUSTED_NAMESPACE);

  @AfterEach
  void clearSession() {
    SessionContext.clear();
  }

  @Test
  void allowsAllFourExactPublicationReadsWithPeerAndRoleFreeInternalJwt() {
    withPeer(
        () -> {
          SessionContext.setContext(
              null, List.of(), Map.of(), true, "forged-but-not-authoritative", "instance-1");
          for (String method : PublicationReadGuard.PUBLICATION_READ_METHODS) {
            guard.requirePublicationRead(method);
          }
        });
  }

  @Test
  void deniesJwtOnlyServiceNameWithoutPeerIdentity() {
    SessionContext.setContext(null, List.of(), Map.of(), true, "game-design-service", "instance-1");
    assertThatThrownBy(
            () -> guard.requirePublicationRead(PublicationReadGuard.WORLD_MANAGEMENT_DIGEST_METHOD))
        .isInstanceOf(AdminAuthorizationException.class);
  }

  @Test
  void deniesMissingPeerIdentity() {
    SessionContext.setContext(null, List.of(), Map.of(), true, "game-design-service", "instance-1");
    assertThatThrownBy(
            () ->
                guard.requirePublicationRead(PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD))
        .isInstanceOf(AdminAuthorizationException.class);
  }

  @Test
  void deniesWrongPeerEvenWhenJwtClaimsGameDesign() {
    GrpcPeerIdentity wrongPeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/firemud/sa/world-management-service",
            TRUSTED_NAMESPACE,
            "world-management-service");
    withPeer(
        wrongPeer,
        () -> {
          SessionContext.setContext(
              null, List.of(), Map.of(), true, "game-design-service", "instance-1");
          assertThatThrownBy(
                  () -> guard.requirePublicationRead(PublicationReadGuard.GAME_LOGIC_DIGEST_METHOD))
              .isInstanceOf(AdminAuthorizationException.class);
        });
  }

  @Test
  void deniesUserAndAdminJwtSubstitutionEvenWithCorrectPeer() {
    withPeer(
        () -> {
          SessionContext.setContext("42", List.of(), Map.of(), false, "game-design-service", null);
          assertThatThrownBy(
                  () ->
                      guard.requirePublicationRead(
                          PublicationReadGuard.AUTOMATION_SCRIPTING_DIGEST_METHOD))
              .isInstanceOf(AdminAuthorizationException.class);

          SessionContext.setContext(
              "42", List.of("platformAdmin"), Map.of(), false, "game-design-service", null);
          assertThatThrownBy(
                  () ->
                      guard.requirePublicationRead(
                          PublicationReadGuard.AUTOMATION_SCRIPTING_DIGEST_METHOD))
              .isInstanceOf(AdminAuthorizationException.class);
        });
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
