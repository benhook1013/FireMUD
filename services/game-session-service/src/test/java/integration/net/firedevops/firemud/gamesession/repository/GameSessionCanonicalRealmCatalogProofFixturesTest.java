package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository.FreshTenantAssociation;
import org.junit.jupiter.api.Test;

/** Verifies synthetic storage fixtures without Docker; it does not authenticate their producer. */
class GameSessionCanonicalRealmCatalogProofFixturesTest {
  @Test
  void syntheticCatalogSetupRetainsTheCompleteCanonicalCaptureAndTerminal() {
    UUID requestId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    String requestDigest = "sha256:" + "a".repeat(64);
    String sourceKey = "synthetic-source-key";
    var creation =
        new FreshTenantCreationEvidence(
            1,
            "test",
            requestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            91L,
            sourceKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "test",
                requestId,
                sourceOperationId,
                requestDigest,
                tenantId,
                91L,
                sourceKey,
                "NEW_GAME_ROW"));
    var association = new FreshTenantAssociation("test", tenantId, 7L, UUID.randomUUID(), creation);
    var fixture = GameSessionCanonicalRealmCatalogProofFixtures.forFreshAssociation(association);

    var decoded = RealmPolicyPublishedEvidenceCodec.decode(fixture.completePolicySetBytes());

    assertThat(decoded.target().canonicalTenantId()).isEqualTo(tenantId);
    assertThat(decoded.operationBytes()).containsExactly(fixture.publishedSet().operationBytes());
    assertThat(decoded.captureBytes()).containsExactly(fixture.publishedSet().captureBytes());
    assertThat(decoded.terminalEvidenceBytes())
        .containsExactly(fixture.publishedSet().terminalEvidenceBytes());
    assertThat(RealmPolicyPublishedEvidenceCodec.encode(decoded))
        .containsExactly(fixture.completePolicySetBytes());
  }
}
