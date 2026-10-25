package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;
import org.junit.jupiter.api.Test;

class CanonicalGameplayAccountAdmissionFenceIntentTest {
  @Test
  void modelCopiesRowsAndNamesThePlannedFenceAsUninstalledIntent() {
    UUID accountId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID plannedFence = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID namespaceId = UUID.randomUUID();
    var cohort =
        new ArrayList<>(
            List.of(
                CanonicalGameplayAccountAdmissionFenceIntent.cohortRow(
                    0,
                    CanonicalGameplayAccountAdmissionFenceIntent.CohortKind.BINDING,
                    "binding-reference",
                    tenantId,
                    namespaceId,
                    "{\"lifecycle\":\"CANDIDATE_PREPARED\"}")));
    var targets =
        new ArrayList<>(
            List.of(
                CanonicalGameplayAccountAdmissionFenceIntent.barrierTarget(
                    0, tenantId, namespaceId)));
    var intent =
        new CanonicalGameplayAccountAdmissionFenceIntent(
            operationId,
            requestId,
            accountId,
            BigInteger.ONE,
            plannedFence,
            BigInteger.TEN,
            new CanonicalGameplayLegacyMigrationDisposition(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.ONE,
                "sha256:" + "a".repeat(64)),
            CanonicalGameplayAccountAdmissionFenceIntent.State.ACQUISITION_INTENT,
            cohort,
            targets,
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64));

    cohort.clear();
    targets.clear();

    assertThat(intent.state())
        .isEqualTo(CanonicalGameplayAccountAdmissionFenceIntent.State.ACQUISITION_INTENT);
    assertThat(intent.plannedAccountAdmissionFence()).isEqualTo(plannedFence);
    assertThat(intent.cohortRows()).hasSize(1);
    assertThat(intent.barrierTargets()).hasSize(1);
  }
}
