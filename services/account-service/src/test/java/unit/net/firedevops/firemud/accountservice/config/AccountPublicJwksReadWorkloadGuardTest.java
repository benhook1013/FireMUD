package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountPublicJwksReadWorkloadGuardTest {
  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void exactPeerRequiresBothCurrentProtectedBindingsAndNoApplicationAuthority() {
    var guard = guard("test", "cluster-a");
    assertThatThrownBy(guard::requireCaller)
        .isInstanceOf(AccountPublicJwksReadWorkloadGuard.ReadDeniedException.class);
    for (String peer :
        List.of(
            "spiffe://firemud/ns/other/sa/game-session-service",
            "spiffe://firemud/ns/test/sa/game-design-service")) {
      withPeer(
          peer,
          () ->
              assertThatThrownBy(guard::requireCaller)
                  .isInstanceOf(AccountPublicJwksReadWorkloadGuard.ReadDeniedException.class));
    }
    withPeer(
        "spiffe://firemud/ns/test/sa/game-session-service",
        () -> {
          assertThat(guard.requireCaller().materializer())
              .isEqualTo(
                  AccountGameplayLoginProtectedSourcesConfigurationTest.materializerBinding());
          assertThatThrownBy(guard("test", "wrong-cluster")::requireCaller)
              .isInstanceOf(AccountPublicJwksReadWorkloadGuard.ReadDeniedException.class);
          assertThatThrownBy(guard("other", "cluster-a")::requireCaller)
              .isInstanceOf(AccountPublicJwksReadWorkloadGuard.ReadDeniedException.class);
          var withdrawn =
              new AccountPublicJwksReadWorkloadGuard(
                  Optional::empty,
                  () ->
                      AccountGameplayLoginProtectedSourcesConfigurationTest.apiBinding(
                          "test", "cluster-a"),
                  "test");
          assertThatThrownBy(withdrawn::requireCaller)
              .isInstanceOf(AccountPublicJwksReadWorkloadGuard.ReadDeniedException.class);
          SessionContext.setContext("account", List.of(), Map.of());
          assertThatThrownBy(guard::requireCaller)
              .isInstanceOf(AccountPublicJwksReadWorkloadGuard.ReadDeniedException.class);
        });
  }

  @Test
  void sourceMustCorrespondToActualApiAndMaterializerIdentity() {
    withPeer(
        "spiffe://firemud/ns/test/sa/game-session-service",
        () -> {
          var caller = guard("test", "cluster-a").requireCaller();
          var api = caller.api();
          var binding = caller.materializer();
          var pin =
              new SourceIdentity(
                  binding.environmentId(),
                  binding.clusterId(),
                  binding.expectedClusterIncarnationUid(),
                  binding.namespace(),
                  binding.expectedNamespaceUid(),
                  "33333333-3333-4333-8333-333333333333",
                  api.configRevision(),
                  api.apiServer().toString(),
                  api.servingCaSha256());
          caller.requireSource(pin);
          assertThatThrownBy(() -> caller.requireSource(null))
              .isExactlyInstanceOf(InvalidJwksException.class);
          for (SourceIdentity changed :
              List.of(
                  new SourceIdentity(
                      pin.environmentId(),
                      pin.clusterId(),
                      pin.clusterIncarnationUid(),
                      pin.namespace(),
                      pin.namespaceUid(),
                      pin.configMapUid(),
                      "different-api-revision",
                      pin.apiServerOrigin(),
                      pin.servingCaSha256()),
                  new SourceIdentity(
                      pin.environmentId(),
                      pin.clusterId(),
                      pin.clusterIncarnationUid(),
                      pin.namespace(),
                      pin.namespaceUid(),
                      pin.configMapUid(),
                      pin.bindingRevision(),
                      "https://different-api.test:6443",
                      pin.servingCaSha256()))) {
            assertThatThrownBy(() -> caller.requireSource(changed))
                .isExactlyInstanceOf(InvalidJwksException.class);
          }
        });
  }

  private static AccountPublicJwksReadWorkloadGuard guard(String namespace, String cluster) {
    return new AccountPublicJwksReadWorkloadGuard(
        () ->
            Optional.of(
                AccountGameplayLoginProtectedSourcesConfigurationTest.materializerBinding()),
        () -> AccountGameplayLoginProtectedSourcesConfigurationTest.apiBinding(namespace, cluster),
        "test");
  }

  private static void withPeer(String uri, Runnable action) {
    Context context =
        Context.ROOT.withValue(
            GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }
}
