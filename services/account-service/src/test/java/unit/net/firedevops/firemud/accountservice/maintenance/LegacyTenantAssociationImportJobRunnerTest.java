package net.firedevops.firemud.accountservice.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class LegacyTenantAssociationImportJobRunnerTest {
  @Test
  void acceptsOnlyExactDedicatedMigratorCertificate() {
    assertThatCode(
            () ->
                LegacyTenantAssociationImportJobRunner.requireJobIdentity(
                    "dev", peer("dev", "account-tenant-migrator")))
        .doesNotThrowAnyException();
    for (GrpcPeerIdentity wrong :
        new GrpcPeerIdentity[] {
          null,
          peer("dev", "account-service"),
          peer("pr-12", "account-tenant-migrator"),
          peer("dev", "game-design-tenant-migrator")
        }) {
      assertThatThrownBy(
              () -> LegacyTenantAssociationImportJobRunner.requireJobIdentity("dev", wrong))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("wrong identity");
    }
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }
}
