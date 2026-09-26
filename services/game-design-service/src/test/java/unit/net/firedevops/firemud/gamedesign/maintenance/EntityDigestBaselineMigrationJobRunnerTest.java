package net.firedevops.firemud.gamedesign.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class EntityDigestBaselineMigrationJobRunnerTest {
  private static final GrpcPeerIdentity MIGRATOR =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/dev/sa/game-design-baseline-migrator",
          "dev",
          "game-design-baseline-migrator");

  @Test
  void acceptsOnlyExactDedicatedIdentityAndTargetEnvironment() {
    assertThatCode(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev", "dev", "game-design-baseline-migrator", MIGRATOR))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongTargetServiceAccountAndCertificateIdentity() {
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev", "pr-12", "game-design-baseline-migrator", MIGRATOR))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev", "dev", "firemud-app", MIGRATOR))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev",
                    "dev",
                    "game-design-baseline-migrator",
                    new GrpcPeerIdentity(
                        "spiffe://firemud/ns/dev/sa/game-design-service",
                        "dev",
                        "game-design-service")))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                EntityDigestBaselineMigrationJobRunner.requireJobAuthority(
                    "dev",
                    "dev",
                    "game-design-baseline-migrator",
                    new GrpcPeerIdentity(
                        "spiffe://firemud/ns/pr-12/sa/game-design-baseline-migrator",
                        "pr-12",
                        "game-design-baseline-migrator")))
        .isInstanceOf(IllegalStateException.class);
  }
}
