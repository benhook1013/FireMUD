package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import org.junit.jupiter.api.Test;

class SelectedEmptyPublicationDigestSupportTest {
  @Test
  void publicationVersionRowMustMatchTheRetainedSelectedTarget() {
    var fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    var freeze = fixture.inputs().worldInventoryReadEvidence().request().freezeEvidence();
    var retainedSelection = freeze.request().accountBinding().input().selection();
    long retainedVersionRowId =
        retainedSelection.selectedCommit().target().gameDesignVersionRowId();
    UUID tenantId = freeze.request().canonicalTenantId();
    UUID versionId = freeze.request().canonicalVersionId();
    UUID selectedCommitId = retainedSelection.selectedCommit().commitId();
    String publishRequestId = freeze.request().publicationRequestId();

    var matchingRequest =
        PublicationDigestRequestBinding.full(
            tenantId.toString(), Long.toString(retainedVersionRowId), publishRequestId);
    SelectedEmptyPublicationDigestSupport.requireOriginalPublicationRequest(
        matchingRequest,
        tenantId,
        retainedVersionRowId,
        versionId,
        selectedCommitId,
        freeze,
        "Entity");

    var mismatchedRequest =
        PublicationDigestRequestBinding.full(
            tenantId.toString(), Long.toString(retainedVersionRowId + 1L), publishRequestId);

    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                SelectedEmptyPublicationDigestSupport.requireOriginalPublicationRequest(
                    mismatchedRequest,
                    tenantId,
                    retainedVersionRowId + 1L,
                    versionId,
                    selectedCommitId,
                    freeze,
                    "Entity"))
        .withMessageContaining(
            "publication version row differs from the original retained World freeze");
  }
}
