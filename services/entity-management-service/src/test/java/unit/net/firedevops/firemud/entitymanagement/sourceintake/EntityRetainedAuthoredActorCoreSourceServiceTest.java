package net.firedevops.firemud.entitymanagement.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadClient;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt;
import net.firedevops.firemud.gamedesign.v1.ParticipantDigest;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures;
import net.firedevops.firemud.testsupport.entity.EntitySelectedSourceIntakeTerminalReadFixtures.Fixture;
import org.junit.jupiter.api.Test;

class EntityRetainedAuthoredActorCoreSourceServiceTest {
  private static final String NAMESPACE = "test";
  private static final String WORKFLOW = "selected-publish-workflow";
  private static final String WORLD_SLUG = "retained-world";
  private static final String ASSOCIATION_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void exactRetryReturnsUnchangedDirectResultAfterIndependentOwnerSnapshotRead() {
    Fixture fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    ReadSetup setup = readSetup(fixture);
    AuthoredInitialActorCoreReadClient client = mock(AuthoredInitialActorCoreReadClient.class);
    EntityEmptySelectedSourceIntakeRepository repository =
        mock(EntityEmptySelectedSourceIntakeRepository.class);
    when(client.read(setup.request())).thenReturn(setup.result());
    when(repository.readPublicationScope(
            NAMESPACE,
            fixture.receipt().canonicalTenantId(),
            fixture.receipt().authorizationBinding().selected().target().gameDesignVersionRowId()))
        .thenReturn(fixture.receipt());
    var service = new EntityRetainedAuthoredActorCoreSourceService(NAMESPACE, client, repository);

    var first = service.read(setup.request());
    var retry = service.read(setup.request());

    assertThat(first).isSameAs(setup.result());
    assertThat(retry).isSameAs(setup.result());
    var inOrder = inOrder(client, repository);
    inOrder.verify(client).read(setup.request());
    inOrder
        .verify(repository)
        .readPublicationScope(
            NAMESPACE,
            fixture.receipt().canonicalTenantId(),
            fixture.receipt().authorizationBinding().selected().target().gameDesignVersionRowId());
    inOrder.verify(client).read(setup.request());
    inOrder
        .verify(repository)
        .readPublicationScope(
            NAMESPACE,
            fixture.receipt().canonicalTenantId(),
            fixture.receipt().authorizationBinding().selected().target().gameDesignVersionRowId());
    verifyNoMoreInteractions(client, repository);
  }

  @Test
  void deniesAReadResultThatSubstitutesTheOriginalOperationBeforeEntitySql() {
    Fixture fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    ReadSetup setup = readSetup(fixture);
    AuthoredInitialActorCoreReadClient client = mock(AuthoredInitialActorCoreReadClient.class);
    EntityEmptySelectedSourceIntakeRepository repository =
        mock(EntityEmptySelectedSourceIntakeRepository.class);
    var substituted = mock(AuthoredInitialActorCoreReadEvidence.ReadResult.class);
    when(substituted.request()).thenReturn(mock(Request.class));
    when(client.read(setup.request())).thenReturn(substituted);
    var service = new EntityRetainedAuthoredActorCoreSourceService(NAMESPACE, client, repository);

    assertThatThrownBy(() -> service.read(setup.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact original request");

    verify(client).read(setup.request());
    verifyNoInteractions(repository);
  }

  @Test
  void deniesMixedProjectionSourceEvenWhenTheEntityReleaseDigestMatches() {
    Fixture fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    ReadSetup setup = readSetup(fixture);
    when(setup.projection().worldSourceOperationId()).thenReturn(UUID.randomUUID());
    AuthoredInitialActorCoreReadClient client = mock(AuthoredInitialActorCoreReadClient.class);
    EntityEmptySelectedSourceIntakeRepository repository =
        mock(EntityEmptySelectedSourceIntakeRepository.class);
    when(client.read(setup.request())).thenReturn(setup.result());
    when(repository.readPublicationScope(
            NAMESPACE,
            fixture.receipt().canonicalTenantId(),
            fixture.receipt().authorizationBinding().selected().target().gameDesignVersionRowId()))
        .thenReturn(fixture.receipt());
    var service = new EntityRetainedAuthoredActorCoreSourceService(NAMESPACE, client, repository);

    assertThatThrownBy(() -> service.read(setup.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact retained source");
  }

  @Test
  void deniesReleaseWithDifferentImmutableEntityRecipientDigest() {
    Fixture fixture = EntitySelectedSourceIntakeTerminalReadFixtures.create();
    ReadSetup setup = readSetup(fixture);
    PublishedReleaseBundle changedRelease =
        setup.release().toBuilder()
            .clearParticipantDigests()
            .addParticipantDigests(entityParticipant(fixture.receipt(), "0".repeat(64)))
            .build();
    when(setup.associationRead().releaseBundle()).thenReturn(changedRelease);
    AuthoredInitialActorCoreReadClient client = mock(AuthoredInitialActorCoreReadClient.class);
    EntityEmptySelectedSourceIntakeRepository repository =
        mock(EntityEmptySelectedSourceIntakeRepository.class);
    when(client.read(setup.request())).thenReturn(setup.result());
    when(repository.readPublicationScope(
            NAMESPACE,
            fixture.receipt().canonicalTenantId(),
            fixture.receipt().authorizationBinding().selected().target().gameDesignVersionRowId()))
        .thenReturn(fixture.receipt());
    var service = new EntityRetainedAuthoredActorCoreSourceService(NAMESPACE, client, repository);

    assertThatThrownBy(() -> service.read(setup.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("release Entity digest");
  }

  @Test
  void deniesUnavailableAuthenticatedGameDesignEvidenceWithoutLocalRead() {
    ReadSetup setup = readSetup(EntitySelectedSourceIntakeTerminalReadFixtures.create());
    AuthoredInitialActorCoreReadClient client = mock(AuthoredInitialActorCoreReadClient.class);
    EntityEmptySelectedSourceIntakeRepository repository =
        mock(EntityEmptySelectedSourceIntakeRepository.class);
    when(client.read(setup.request())).thenThrow(new IllegalStateException("read unavailable"));
    var service = new EntityRetainedAuthoredActorCoreSourceService(NAMESPACE, client, repository);

    assertThatThrownBy(() -> service.read(setup.request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("read unavailable");

    verifyNoInteractions(repository);
  }

  private static ReadSetup readSetup(Fixture fixture) {
    EntityEmptySelectedSourceIntakeReceipt receipt = fixture.receipt();
    ExactReplay replay =
        new ExactReplay(
            receipt.canonicalVersionId(), receipt.selectedCommitId(), WORKFLOW, ASSOCIATION_DIGEST);
    Request originalRequest = mock(Request.class);
    when(originalRequest.targetNamespace()).thenReturn(NAMESPACE);
    when(originalRequest.selection()).thenReturn(replay);
    var request = new AuthoredInitialActorCoreReadEvidence.ReadRequest(originalRequest);

    PublicOwnerScope worldOwner =
        receipt.inputs().worldInventoryReadEvidence().inventory().publicEvidence().ownerScope();
    Association association = mock(Association.class);
    when(association.canonicalTenantId()).thenReturn(receipt.canonicalTenantId());
    when(association.canonicalVersionId()).thenReturn(receipt.canonicalVersionId());
    when(association.selectedCommitId()).thenReturn(receipt.selectedCommitId());
    when(association.publishWorkflowId()).thenReturn(WORKFLOW);
    when(association.associationDigest()).thenReturn(ASSOCIATION_DIGEST);
    when(association.targetNamespace()).thenReturn(NAMESPACE);
    when(association.intakeRequestId()).thenReturn(worldOwner.intakeRequestId());
    when(association.worldOperationId()).thenReturn(worldOwner.intakeOperationId());
    when(association.publicationSelectionDigest())
        .thenReturn(
            receipt
                .inputs()
                .worldInventoryReadEvidence()
                .request()
                .freezeEvidence()
                .request()
                .accountBinding()
                .input()
                .selection()
                .digest());
    when(association.sourceOperationId()).thenReturn(worldOwner.sourceOperationId());
    when(association.sourceEvidenceDigest()).thenReturn(worldOwner.sourceEvidenceDigest());
    when(association.worldSlug()).thenReturn(WORLD_SLUG);
    PublicReceipt worldReceipt = mock(PublicReceipt.class);
    when(worldReceipt.targetNamespace()).thenReturn(worldOwner.targetNamespace());
    when(worldReceipt.canonicalTenantId()).thenReturn(worldOwner.canonicalTenantId());
    when(worldReceipt.intakeRequestId()).thenReturn(worldOwner.intakeRequestId());
    when(worldReceipt.operationId()).thenReturn(worldOwner.intakeOperationId());
    when(worldReceipt.sourceOperationId()).thenReturn(worldOwner.sourceOperationId());
    when(worldReceipt.sourceEvidenceDigest()).thenReturn(worldOwner.sourceEvidenceDigest());
    when(worldReceipt.receiptDigest()).thenReturn(worldOwner.intakeReceiptDigest());
    when(association.worldReceipt()).thenReturn(worldReceipt);

    ParticipantDigest entityParticipant = entityParticipant(receipt, null);
    PublishedReleaseBundle release =
        PublishedReleaseBundle.newBuilder()
            .setVersionId(
                receipt.authorizationBinding().selected().target().gameDesignVersionRowId())
            .setAttestationSchemaVersion("v4")
            .setPublishWorkflowId(WORKFLOW)
            .setCanonicalTenantId(receipt.canonicalTenantId().toString())
            .setCanonicalVersionId(receipt.canonicalVersionId().toString())
            .addParticipantDigests(entityParticipant)
            .build();
    Result associationRead = mock(Result.class);
    when(associationRead.request()).thenReturn(originalRequest);
    when(associationRead.association()).thenReturn(association);
    when(associationRead.releaseBundle()).thenReturn(release);

    AuthoredInitialActorCoreProjection projection = mock(AuthoredInitialActorCoreProjection.class);
    when(projection.canonicalTenantId()).thenReturn(receipt.canonicalTenantId());
    when(projection.canonicalVersionId()).thenReturn(receipt.canonicalVersionId());
    when(projection.selectedCommitId()).thenReturn(receipt.selectedCommitId());
    when(projection.selectedCommitDigest())
        .thenReturn(receipt.authorizationBinding().selected().digest());
    when(projection.worldSourceOperationId()).thenReturn(worldOwner.sourceOperationId());
    when(projection.worldSourceEvidenceDigest()).thenReturn(worldOwner.sourceEvidenceDigest());
    when(projection.worldSlug()).thenReturn(WORLD_SLUG);
    when(projection.publicationWorkflowId()).thenReturn(WORKFLOW);

    AuthoredInitialActorCoreReadEvidence.ReadResult result =
        mock(AuthoredInitialActorCoreReadEvidence.ReadResult.class);
    when(result.request()).thenReturn(originalRequest);
    when(result.associationRead()).thenReturn(associationRead);
    when(result.projections()).thenReturn(List.of(projection));
    return new ReadSetup(request, result, associationRead, release, projection);
  }

  private static ParticipantDigest entityParticipant(
      EntityEmptySelectedSourceIntakeReceipt receipt, String replacementDigest) {
    var target = receipt.authorizationBinding().selected().target();
    String inventoryJson =
        receipt.inputs().ownerSourceInventoryDeclaration().entityInventory().canonicalJson();
    String contentDigest =
        EntitySelectedEmptyPublicationDigestService.contentDigest(
            receipt.canonicalTenantId(),
            target.gameDesignVersionRowId(),
            receipt.canonicalVersionId(),
            inventoryJson);
    return ParticipantDigest.newBuilder()
        .setParticipantKey("ENTITY_MANAGEMENT")
        .setScopeValue(Long.toString(target.gameDesignVersionRowId()))
        .setAppliedCommitId(receipt.selectedCommitId().toString())
        .setContentDigest(replacementDigest == null ? contentDigest : replacementDigest)
        .setDigestSchemaVersion(EntitySelectedEmptyPublicationDigestService.DIGEST_SCHEMA_VERSION)
        .build();
  }

  private record ReadSetup(
      AuthoredInitialActorCoreReadEvidence.ReadRequest request,
      AuthoredInitialActorCoreReadEvidence.ReadResult result,
      Result associationRead,
      PublishedReleaseBundle release,
      AuthoredInitialActorCoreProjection projection) {}
}
