package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.Test;

class WorldDesignPublicationFenceEvidenceTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INTAKE_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID INTAKE_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String SOURCE_EVIDENCE_DIGEST = "sha256:" + "a".repeat(64);
  private static final String FULL_REQUEST_DIGEST = "f".repeat(64);
  private static final String CONTENT_DIGEST = "b".repeat(64);

  @Test
  void bindsFullVersionToTheCanonicalPublicationDigestRequestGrammar() {
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(TENANT_ID.toString(), "42", "request-7");
    WorldDesignPublicationFenceEvidence evidence =
        evidence("request-7", FULL_REQUEST_DIGEST, binding.derivedWorkflowIdentity());

    assertThat(evidence.publicationBinding()).usingRecursiveComparison().isEqualTo(binding);
    assertThat(evidence.publicationBinding().scopeKindValue()).isEqualTo("FULL_VERSION");
    assertThat(evidence.publicationBinding().versionId()).isEqualTo("42");
    assertThat(evidence.publicationBinding().baseVersionId()).isEmpty();
    assertThat(evidence.publicationBinding().scriptPatchVersion()).isEmpty();
    assertThat(evidence.requestDigest()).isEqualTo(FULL_REQUEST_DIGEST);
    assertThat(evidence.requestDigest()).isNotEqualTo(binding.requestDigest());
    assertThat(evidence.publishWorkflowId())
        .isEqualTo("publish:" + TENANT_ID + ":publish-request:request-7");
  }

  @Test
  void rejectsChangedOrOmittedRequestAndWorkflowBindings() {
    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(TENANT_ID.toString(), "42", "request-7");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> evidence("request-7", binding.requestDigest(), "publish:wrong"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> evidence("request-7", null, binding.derivedWorkflowIdentity()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> evidence("request-7", "F".repeat(64), binding.derivedWorkflowIdentity()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> evidence("request-7", binding.requestDigest(), null));
  }

  @Test
  void rejectsMalformedScopeAndAuthoredSourceIdentifiers() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> evidence("request-7", validDigest(), validWorkflow(), 0, 1L));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> evidence("request-7", validDigest(), validWorkflow(), 42, 0L));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDesignPublicationFenceEvidence(
                    "FireMUD",
                    TENANT_ID,
                    42,
                    INTAKE_REQUEST_ID,
                    INTAKE_OPERATION_ID,
                    SOURCE_OPERATION_ID,
                    SOURCE_EVIDENCE_DIGEST,
                    "request-7",
                    validDigest(),
                    9,
                    validWorkflow()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDesignPublicationFenceEvidence(
                    "firemud",
                    new UUID(0L, 0L),
                    42,
                    INTAKE_REQUEST_ID,
                    INTAKE_OPERATION_ID,
                    SOURCE_OPERATION_ID,
                    SOURCE_EVIDENCE_DIGEST,
                    "request-7",
                    validDigest(),
                    9,
                    validWorkflow()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDesignPublicationFenceEvidence(
                    "firemud",
                    TENANT_ID,
                    42,
                    INTAKE_REQUEST_ID,
                    INTAKE_OPERATION_ID,
                    SOURCE_OPERATION_ID,
                    "a".repeat(64),
                    "request-7",
                    validDigest(),
                    9,
                    validWorkflow()));
  }

  @Test
  void checkpointRequiresACompleteCanonicalDigestTuple() {
    var checkpoint =
        new WorldDesignPublicationFenceEvidence.Checkpoint("version:42", CONTENT_DIGEST, 2);

    assertThat(checkpoint.appliedCommitId()).isEqualTo("version:42");
    assertThat(checkpoint.contentDigest()).isEqualTo(CONTENT_DIGEST);
    assertThat(checkpoint.digestSchemaVersion()).isPositive();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new WorldDesignPublicationFenceEvidence.Checkpoint("", CONTENT_DIGEST, 2));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new WorldDesignPublicationFenceEvidence.Checkpoint(" \t", CONTENT_DIGEST, 2));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDesignPublicationFenceEvidence.Checkpoint(
                    "version:42", "B".repeat(64), 2));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDesignPublicationFenceEvidence.Checkpoint(
                    "version:42", CONTENT_DIGEST, 0));
  }

  private static WorldDesignPublicationFenceEvidence evidence(
      String requestId, String digest, String workflowId) {
    return evidence(requestId, digest, workflowId, 42, 9L);
  }

  private static WorldDesignPublicationFenceEvidence evidence(
      String requestId, String digest, String workflowId, long versionId, long epoch) {
    return new WorldDesignPublicationFenceEvidence(
        "firemud",
        TENANT_ID,
        versionId,
        INTAKE_REQUEST_ID,
        INTAKE_OPERATION_ID,
        SOURCE_OPERATION_ID,
        SOURCE_EVIDENCE_DIGEST,
        requestId,
        digest,
        epoch,
        workflowId);
  }

  private static String validDigest() {
    return FULL_REQUEST_DIGEST;
  }

  private static String validWorkflow() {
    return PublicationDigestRequestBinding.full(TENANT_ID.toString(), "42", "request-7")
        .derivedWorkflowIdentity();
  }
}
