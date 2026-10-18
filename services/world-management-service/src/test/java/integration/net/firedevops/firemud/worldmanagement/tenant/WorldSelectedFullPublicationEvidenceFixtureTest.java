package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class WorldSelectedFullPublicationEvidenceFixtureTest {
  @Test
  void rejectsVersionWithDifferentSourceIntakeOperationBeforeSqlOrAccountCallback() {
    UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID versionId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID intakeOperationId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    UUID versionIntakeOperationId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    var target =
        new TargetProof(tenant, versionId, 17L, "tenant-key", 23L, "tenant-key", "NEW_GAME_ROW");
    var intent =
        new PublishIntent(
            tenant,
            versionId,
            "publication-1",
            "1",
            "controlled test",
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "sha256:" + "a".repeat(64));
    var selection = mock(AuthoredDraftPublishSelectionBinding.class);
    var commit = mock(DraftCommitBinding.class);
    var intake = mock(WorldAuthoredSourceIntakeReceipt.class);
    var versionIntake = mock(WorldAuthoredSourceIntakeReceipt.class);
    var version = mock(WorldAuthoredVersionIdentityReceipt.class);
    var graph = mock(WorldSelectedFullPublicationEvidenceFixture.AppliedGraph.class);
    var worldDsl = mock(DSLContext.class);
    var transactions = mock(PlatformTransactionManager.class);
    @SuppressWarnings("unchecked")
    Function<AuthoredDraftPublishSelectionBinding, AccountPublicationAuthorizationBinding>
        accountBindingFactory = mock(Function.class);

    when(selection.target()).thenReturn(target);
    when(selection.intent()).thenReturn(intent);
    when(selection.selectedCommit()).thenReturn(commit);
    when(graph.selectedCommit()).thenReturn(commit);
    when(graph.sourceIntake()).thenReturn(intake);
    when(graph.versionIdentity()).thenReturn(version);
    when(intake.targetNamespace()).thenReturn("test");
    when(intake.canonicalTenantId()).thenReturn(tenant);
    when(intake.operationId()).thenReturn(intakeOperationId);
    when(version.canonicalTenantId()).thenReturn(tenant);
    when(version.canonicalVersionId()).thenReturn(versionId);
    when(version.gameDesignVersionId()).thenReturn(17L);
    when(version.sourceIntakeReceipt()).thenReturn(versionIntake);
    when(versionIntake.operationId()).thenReturn(versionIntakeOperationId);

    assertThatThrownBy(
            () ->
                WorldSelectedFullPublicationEvidenceFixture.applyFreezeAndCapture(
                    worldDsl, transactions, "test", selection, graph, accountBindingFactory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("World retained source/Version rows differ");

    verifyNoInteractions(worldDsl, transactions);
    verify(accountBindingFactory, never()).apply(any());
  }
}
