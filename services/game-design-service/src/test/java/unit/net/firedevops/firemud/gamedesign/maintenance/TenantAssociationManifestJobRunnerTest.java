package net.firedevops.firemud.gamedesign.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class TenantAssociationManifestJobRunnerTest {
  @Test
  void acceptsOnlyExactDedicatedMigratorCertificate() {
    assertThatCode(
            () ->
                TenantAssociationManifestJobRunner.requireJobIdentity(
                    "dev", peer("dev", "game-design-tenant-migrator")))
        .doesNotThrowAnyException();
    for (GrpcPeerIdentity wrong :
        new GrpcPeerIdentity[] {
          null,
          peer("dev", "game-design-service"),
          peer("pr-12", "game-design-tenant-migrator"),
          peer("dev", "account-tenant-migrator")
        }) {
      assertThatThrownBy(() -> TenantAssociationManifestJobRunner.requireJobIdentity("dev", wrong))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("wrong identity");
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }
}
