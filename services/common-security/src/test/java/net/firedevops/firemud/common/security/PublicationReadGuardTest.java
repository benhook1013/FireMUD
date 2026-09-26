package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublicationReadGuardTest {
  private static final String TRUSTED_NAMESPACE = "firemud";
  private static final GrpcPeerIdentity GAME_DESIGN_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/firemud/sa/game-design-service",
          TRUSTED_NAMESPACE,
          "game-design-service");
  private static final GrpcPeerIdentity ENTITY_BASELINE_MIGRATOR_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/firemud/sa/game-design-baseline-migrator",
          TRUSTED_NAMESPACE,
          "game-design-baseline-migrator");

  private final PublicationReadGuard guard = new PublicationReadGuard(TRUSTED_NAMESPACE);

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void protectsExactlyTheGeneratedPublicationDigestMethods() {
    Set<String> expectedMethods =
        Set.of(
            "world_management.v1.WorldManagementService/GetDraftDesignDigest",
            "entity_management.v1.EntityManagementService/GetDraftDesignDigest",
            "game_logic.v1.GameLogicService/GetDraftDesignDigest",
            "automation_scripting.v1.AutomationScriptingService/GetDraftDesignDigest");
    Set<String> descriptorMethods =
        Set.of(
            WorldManagementServiceGrpc.getGetDraftDesignDigestMethod().getFullMethodName(),
            EntityManagementServiceGrpc.getGetDraftDesignDigestMethod().getFullMethodName(),
            GameLogicServiceGrpc.getGetDraftDesignDigestMethod().getFullMethodName(),
            AutomationScriptingServiceGrpc.getGetDraftDesignDigestMethod().getFullMethodName());

    assertThat(PublicationReadGuard.PUBLICATION_READ_METHODS).isEqualTo(expectedMethods);
    assertThat(PublicationReadGuard.PUBLICATION_READ_METHODS).isEqualTo(descriptorMethods);
  }

  @Test
  void allowsAllFourExactPublicationReadsWithWorkloadOnlyContext() {
    withPeer(
        () -> {
          SessionContext.setContext(
              null, List.of(), Map.of(), true, "game-design-service", "game-design-instance");
          for (String method : PublicationReadGuard.PUBLICATION_READ_METHODS) {
            guard.requirePublicationRead(method);
          }
        });
  }

  @Test
  void baselineMigratorCanReadOnlyTheEntityDigestWithoutUserContext() {
    withPeer(
        ENTITY_BASELINE_MIGRATOR_PEER,
        () -> {
          guard.requirePublicationRead(PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD);
          for (String method : PublicationReadGuard.PUBLICATION_READ_METHODS) {
            if (!method.equals(PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD)) {
              assertThatThrownBy(() -> guard.requirePublicationRead(method))
                  .isInstanceOf(AdminAuthorizationException.class);
            }
          }
          SessionContext.setContext("7", List.of("platformAdmin"), Map.of());
          assertThatThrownBy(
                  () ->
                      guard.requirePublicationRead(
                          PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD))
              .isInstanceOf(AdminAuthorizationException.class);
        });
  }

  @Test
  void baselineMigratorFromAnotherNamespaceCannotReadEntityDigest() {
    withPeer(
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/other/sa/game-design-baseline-migrator",
            "other",
            "game-design-baseline-migrator"),
        () ->
            assertThatThrownBy(
                    () ->
                        guard.requirePublicationRead(
                            PublicationReadGuard.ENTITY_MANAGEMENT_DIGEST_METHOD))
                .isInstanceOf(AdminAuthorizationException.class));
  }

  @Test
  void deniesAuthenticatedUserContextEvenWithGameDesignPeer() {
    withPeer(
        () -> {
          SessionContext.setContext("42", List.of(), Map.of());
          assertThatThrownBy(
                  () ->
                      guard.requirePublicationRead(
                          PublicationReadGuard.WORLD_MANAGEMENT_DIGEST_METHOD))
              .isInstanceOf(AdminAuthorizationException.class);
        });
  }

  @Test
  void deniesAuthenticatedAdminContextEvenWithGameDesignPeer() {
    withPeer(
        () -> {
          SessionContext.setContext("7", List.of("platformAdmin"), Map.of());
          assertThatThrownBy(
                  () ->
                      guard.requirePublicationRead(
                          PublicationReadGuard.AUTOMATION_SCRIPTING_DIGEST_METHOD))
              .isInstanceOf(AdminAuthorizationException.class);
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
            "spiffe://firemud/ns/other/sa/game-design-service", "other", "game-design-service");
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
