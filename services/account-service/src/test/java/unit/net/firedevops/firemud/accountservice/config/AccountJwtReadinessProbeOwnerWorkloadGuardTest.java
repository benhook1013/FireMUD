package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountJwtReadinessProbeOwnerWorkloadGuardTest {
  private static final String NAMESPACE = "firemud-prod";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void requiresExactGameSessionPeerAndNamespaceFromBothProtectedAndLocalBindings() {
    var guard = guard(NAMESPACE);

    assertThatThrownBy(guard::requireGameSessionOwnerReadCaller)
        .isInstanceOf(AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException.class);

    withPeer(
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/other/sa/game-session-service", "other", "game-session-service"),
        () ->
            assertThatThrownBy(guard::requireGameSessionOwnerReadCaller)
                .isInstanceOf(
                    AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException.class));

    withPeer(
        peer(NAMESPACE),
        () ->
            assertThat(guard.requireGameSessionOwnerReadCaller().accountBinding().namespace())
                .isEqualTo(NAMESPACE));
  }

  @Test
  void rejectsDisabledMismatchedAndSessionContextCallers() {
    var enabled = guard(NAMESPACE);
    var disabled =
        new AccountJwtReadinessProbeOwnerWorkloadGuard(
            Optional::empty, () -> apiBinding(NAMESPACE), NAMESPACE);
    withPeer(
        peer(NAMESPACE),
        () ->
            assertThatThrownBy(disabled::requireGameSessionOwnerReadCaller)
                .isInstanceOf(
                    AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException.class));

    var localMismatch = guard("other");
    withPeer(
        peer(NAMESPACE),
        () ->
            assertThatThrownBy(localMismatch::requireGameSessionOwnerReadCaller)
                .isInstanceOf(
                    AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException.class));

    withPeer(
        peer(NAMESPACE),
        () -> {
          SessionContext.setContext("authenticated-account", List.of(), java.util.Map.of());
          assertThatThrownBy(enabled::requireGameSessionOwnerReadCaller)
              .isInstanceOf(
                  AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException.class);
        });
  }

  private static AccountJwtReadinessProbeOwnerWorkloadGuard guard(String localNamespace) {
    Binding binding = binding(NAMESPACE);
    return new AccountJwtReadinessProbeOwnerWorkloadGuard(
        () -> Optional.of(binding), () -> apiBinding(NAMESPACE), localNamespace);
  }

  private static Binding binding(String namespace) {
    String revision = "materializer-r1";
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String expectedPeer = "spiffe://firemud/ns/" + namespace + "/sa/jwt-signer-materializer";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision, "prod", "cluster-a", namespace, clusterUid, namespaceUid, expectedPeer, pins);
    return new Binding(
        "prod",
        "cluster-a",
        namespace,
        clusterUid,
        namespaceUid,
        expectedPeer,
        pins,
        revision,
        digest);
  }

  private static AccountJwtJwksApiBinding.ParsedBinding apiBinding(String namespace) {
    return new AccountJwtJwksApiBinding.ParsedBinding(
        "api-r1",
        "prod",
        "cluster-a",
        URI.create("https://kubernetes.example.test:6443"),
        "kubernetes.example.test",
        Path.of("protected").toAbsolutePath().resolve("ca.pem"),
        "b".repeat(64),
        Path.of("protected").toAbsolutePath().resolve("token"),
        namespace,
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "system:serviceaccount:" + namespace + ":account-service",
        "c".repeat(64));
  }

  private static GrpcPeerIdentity peer(String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/game-session-service",
        namespace,
        "game-session-service");
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.ROOT.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }
}
