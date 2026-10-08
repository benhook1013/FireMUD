package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountJwtReadinessPodReceiverWorkloadGuardTest {
  private static final String NAMESPACE = "firemud-prod";

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void requiresExactAccountPeerAndMatchingProtectedAccountBindings() {
    var guard = guard(NAMESPACE);
    assertThatThrownBy(guard::requireAccountReceiverCaller)
        .isInstanceOf(
            AccountJwtReadinessPodReceiverWorkloadGuard.ReceiverCallerDeniedException.class);

    withPeer(
        peer("other", "account-service"),
        () ->
            assertThatThrownBy(guard::requireAccountReceiverCaller)
                .isInstanceOf(
                    AccountJwtReadinessPodReceiverWorkloadGuard.ReceiverCallerDeniedException
                        .class));
    withPeer(
        peer(NAMESPACE, "game-session-service"),
        () ->
            assertThatThrownBy(guard::requireAccountReceiverCaller)
                .isInstanceOf(
                    AccountJwtReadinessPodReceiverWorkloadGuard.ReceiverCallerDeniedException
                        .class));

    withPeer(
        peer(NAMESPACE, "account-service"),
        () ->
            assertThat(guard.requireAccountReceiverCaller().accountBinding().namespace())
                .isEqualTo(NAMESPACE));
  }

  @Test
  void rejectsSessionContextAndProtectedTrustDriftAcrossVerification() {
    Binding original = binding(NAMESPACE, "materializer-r1");
    AtomicReference<Binding> current = new AtomicReference<>(original);
    var guard =
        new AccountJwtReadinessPodReceiverWorkloadGuard(
            () -> Optional.of(current.get()), () -> apiBinding(NAMESPACE), NAMESPACE);

    withPeer(
        peer(NAMESPACE, "account-service"),
        () -> {
          var caller = guard.requireAccountReceiverCaller();
          guard.requireUnchanged(caller);
          current.set(binding(NAMESPACE, "materializer-r2"));
          assertThatThrownBy(() -> guard.requireUnchanged(caller))
              .isInstanceOf(
                  AccountJwtReadinessPodReceiverWorkloadGuard.ReceiverCallerDeniedException.class);

          SessionContext.setContext("authenticated-account", List.of(), Map.of());
          assertThatThrownBy(guard::requireAccountReceiverCaller)
              .isInstanceOf(
                  AccountJwtReadinessPodReceiverWorkloadGuard.ReceiverCallerDeniedException.class);
        });
  }

  private static AccountJwtReadinessPodReceiverWorkloadGuard guard(String localNamespace) {
    Binding binding = binding(NAMESPACE, "materializer-r1");
    return new AccountJwtReadinessPodReceiverWorkloadGuard(
        () -> Optional.of(binding), () -> apiBinding(NAMESPACE), localNamespace);
  }

  private static Binding binding(String namespace, String revision) {
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

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
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
