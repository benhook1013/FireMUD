package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class StartSessionPublishedRealmPolicyReadServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();
  private static final UUID SOURCE_OPERATION = UUID.randomUUID();
  private static final UUID COMMIT = UUID.randomUUID();
  private static final String SOURCE_DIGEST = "sha256:" + "a".repeat(64);
  private static final String RELEASE_DIGEST = "sha256:" + "c".repeat(64);
  private static final String WORLD = "foundry";
  private static final long SOURCE_ROW_ID = 41L;
  private static final String SOURCE_TENANT_KEY = "private-tenant";
  private static final String PROVENANCE = "NEW_GAME_ROW";
  private static final String RELEASE_REF = "release/immutable-41";
  private static final String WORKFLOW = "publish-workflow-41";
  private static final String MANIFEST = "sha256:" + "b".repeat(64);
  private static final String GENERATION = "gen-1";

  @Test
  void readsExactSourceAndSealedPolicyInsideIndependentReadOnlyRepeatableReadSnapshot() {
    var sources = mock(GameAuthoredWorldSourceRepository.class);
    var policies = mock(RealmPolicyPublicationRepository.class);
    var transactions = spy(transactionManager());
    var target = target();
    var descriptor = descriptor();
    var release = release();
    var source = source();
    var published = published(target, COMMIT, 8L);
    when(sources.readExactSourceSnapshot(NAMESPACE, SOURCE_OPERATION, TENANT, WORLD, SOURCE_DIGEST))
        .thenReturn(Optional.of(source));
    when(policies.readPublishedSet(target)).thenReturn(Optional.of(published));
    var service = new StartSessionPublishedRealmPolicyReadService(sources, policies, transactions);

    try (var readback = stubTerminal(published, 8L, MANIFEST)) {
      var result =
          service.read(
              new StartSessionPublishedRealmPolicyReadService.ReadRequest(
                  descriptor, release, target));

      assertThat(result.sourceEvidence()).isSameAs(source);
      assertThat(result.publishedSet()).isSameAs(published);
      var order = inOrder(transactions, sources, policies);
      order.verify(transactions).getTransaction(any(TransactionDefinition.class));
      order
          .verify(sources)
          .readExactSourceSnapshot(NAMESPACE, SOURCE_OPERATION, TENANT, WORLD, SOURCE_DIGEST);
      order.verify(policies).readPublishedSet(target);
    }
  }

  @Test
  void failsClosedWhenTheExactSourceOrSealedSetIsMissing() {
    var sources = mock(GameAuthoredWorldSourceRepository.class);
    var policies = mock(RealmPolicyPublicationRepository.class);
    var target = target();
    var descriptor = descriptor();
    var release = release();
    when(sources.readExactSourceSnapshot(NAMESPACE, SOURCE_OPERATION, TENANT, WORLD, SOURCE_DIGEST))
        .thenReturn(Optional.empty());
    var service =
        new StartSessionPublishedRealmPolicyReadService(sources, policies, transactionManager());
    var request =
        new StartSessionPublishedRealmPolicyReadService.ReadRequest(descriptor, release, target);

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("AUTHORED_WORLD_SOURCE_UNAVAILABLE");
    verifyNoInteractions(policies);

    var source = source();
    when(sources.readExactSourceSnapshot(NAMESPACE, SOURCE_OPERATION, TENANT, WORLD, SOURCE_DIGEST))
        .thenReturn(Optional.of(source));
    when(policies.readPublishedSet(target)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("PUBLISHED_REALM_POLICY_SET_UNAVAILABLE");
  }

  @Test
  void rejectsReleaseReferenceSubstitution() {
    var target = target();
    var descriptor = descriptor();
    var release = release();
    var sources = mock(GameAuthoredWorldSourceRepository.class);
    var policies = mock(RealmPolicyPublicationRepository.class);
    var published = published(target, COMMIT, 8L);
    var source = source();
    when(sources.readExactSourceSnapshot(NAMESPACE, SOURCE_OPERATION, TENANT, WORLD, SOURCE_DIGEST))
        .thenReturn(Optional.of(source));
    when(policies.readPublishedSet(target)).thenReturn(Optional.of(published));
    when(published.publishedReleaseBundleRef()).thenReturn("release/substituted");
    var service =
        new StartSessionPublishedRealmPolicyReadService(sources, policies, transactionManager());

    try (var readback = stubTerminal(published, 8L, MANIFEST)) {
      assertReleaseConflict(service, descriptor, release, target);
    }
  }

  @Test
  void rejectsChangedCommitEpochAndReleaseManifestEvidence() {
    assertRejectedReadback(COMMIT, 10L, MANIFEST);
    assertRejectedReadback(UUID.randomUUID(), 8L, MANIFEST);
    assertRejectedReadback(COMMIT, 8L, "sha256:" + "d".repeat(64));
  }

  private static void assertRejectedReadback(UUID commit, long publicationEpoch, String manifest) {
    var target = target();
    var descriptor = descriptor();
    var release = release();
    var sources = mock(GameAuthoredWorldSourceRepository.class);
    var policies = mock(RealmPolicyPublicationRepository.class);
    var published = published(target, commit, publicationEpoch);
    var source = source();
    when(sources.readExactSourceSnapshot(NAMESPACE, SOURCE_OPERATION, TENANT, WORLD, SOURCE_DIGEST))
        .thenReturn(Optional.of(source));
    when(policies.readPublishedSet(target)).thenReturn(Optional.of(published));
    var service =
        new StartSessionPublishedRealmPolicyReadService(sources, policies, transactionManager());

    try (var readback = stubTerminal(published, publicationEpoch, manifest)) {
      assertReleaseConflict(service, descriptor, release, target);
    }
  }

  private static void assertReleaseConflict(
      StartSessionPublishedRealmPolicyReadService service,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence release,
      TargetProof target) {
    assertThatThrownBy(
            () ->
                service.read(
                    new StartSessionPublishedRealmPolicyReadService.ReadRequest(
                        descriptor, release, target)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("START_SESSION_POLICY_RELEASE_BINDING_CONFLICT");
  }

  private static ReadbackFixture stubTerminal(
      RealmPolicyPublishedEvidence.PublishedSet published,
      long publicationEpoch,
      String terminalManifest) {
    var content = mock(GameDesignPublicationTerminalEvidence.ReleaseContent.class);
    when(content.canonicalTenantId()).thenReturn(TENANT);
    when(content.canonicalVersionId()).thenReturn(VERSION);
    when(content.publishedReleaseBundleRef()).thenReturn(RELEASE_REF);
    when(content.versionNumber()).thenReturn(1);
    when(content.attestationSchemaVersion()).thenReturn("v2");
    when(content.publishWorkflowId()).thenReturn(WORKFLOW);
    when(content.manifestHash()).thenReturn(terminalManifest);
    when(content.manifestSchemaVersion()).thenReturn(1);
    when(content.requiredManifestAssetKeys()).thenReturn(List.of());
    when(content.artifactDigests()).thenReturn(List.of());
    when(content.commandDefinitions()).thenReturn(List.of());
    when(content.generationConfigRevision()).thenReturn(GENERATION);
    when(content.worldStartLocationEvidence()).thenReturn(null);
    when(content.participantDigests()).thenReturn(List.of());
    var terminal = mock(GameDesignPublicationTerminalEvidence.class);
    when(terminal.releaseContent()).thenReturn(content);
    when(terminal.outcome()).thenReturn(GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED);
    when(terminal.publicationVersionStateEpoch()).thenReturn(publicationEpoch);
    when(terminal.publishedReleaseBundleRef()).thenReturn(RELEASE_REF);
    when(terminal.publishedReleaseBundleDigest()).thenReturn(RELEASE_DIGEST);
    byte[] bytes = {1};
    when(published.terminalEvidenceBytes()).thenReturn(bytes);
    var decoder = Mockito.mockStatic(GameDesignPublicationTerminalEvidence.class);
    decoder
        .when(() -> GameDesignPublicationTerminalEvidence.fromStored(bytes))
        .thenReturn(terminal);
    return new ReadbackFixture(decoder);
  }

  private static PlatformTransactionManager transactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        var requiredDefinition =
            definition == null ? TransactionDefinition.withDefaults() : definition;
        assertThat(requiredDefinition.getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(requiredDefinition.getIsolationLevel())
            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(requiredDefinition.isReadOnly()).isTrue();
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  private static TargetProof target() {
    return new TargetProof(
        TENANT, VERSION, 17L, SOURCE_TENANT_KEY, SOURCE_ROW_ID, SOURCE_TENANT_KEY, PROVENANCE);
  }

  private static AuthoredWorldSourceEvidence source() {
    var source = mock(AuthoredWorldSourceEvidence.class);
    when(source.targetNamespace()).thenReturn(NAMESPACE);
    when(source.operationId()).thenReturn(SOURCE_OPERATION);
    when(source.evidenceDigest()).thenReturn(SOURCE_DIGEST);
    when(source.canonicalTenantId()).thenReturn(TENANT);
    when(source.worldSlug()).thenReturn(WORLD);
    when(source.sourceGameRowId()).thenReturn(SOURCE_ROW_ID);
    when(source.sourceGameTenantKey()).thenReturn(SOURCE_TENANT_KEY);
    when(source.provenanceKind()).thenReturn(PROVENANCE);
    return source;
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptor() {
    var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
    when(descriptor.targetNamespace()).thenReturn(NAMESPACE);
    when(descriptor.authoredWorldSourceOperationId()).thenReturn(SOURCE_OPERATION);
    when(descriptor.authoredWorldSourceEvidenceDigest()).thenReturn(SOURCE_DIGEST);
    when(descriptor.canonicalTenantId()).thenReturn(TENANT);
    when(descriptor.worldSlug()).thenReturn(WORLD);
    when(descriptor.versionId()).thenReturn(17L);
    when(descriptor.versionStateEpoch()).thenReturn(9L);
    when(descriptor.publishedReleaseBundleRef()).thenReturn(RELEASE_REF);
    return descriptor;
  }

  private static AuthoredWorldReleaseAttestationEvidence release() {
    var release = mock(AuthoredWorldReleaseAttestationEvidence.class);
    when(release.targetNamespace()).thenReturn(NAMESPACE);
    when(release.authoredWorldSourceOperationId()).thenReturn(SOURCE_OPERATION);
    when(release.authoredWorldSourceEvidenceDigest()).thenReturn(SOURCE_DIGEST);
    when(release.canonicalTenantId()).thenReturn(TENANT);
    when(release.canonicalVersionId()).thenReturn(VERSION);
    when(release.worldSlug()).thenReturn(WORLD);
    when(release.schemaVersion())
        .thenReturn(AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
    when(release.publishedReleaseBundleRef()).thenReturn(RELEASE_REF);
    when(release.versionStateEpoch()).thenReturn(9L);
    when(release.publishWorkflowId()).thenReturn(WORKFLOW);
    when(release.commitId()).thenReturn(COMMIT.toString());
    when(release.participantDigests()).thenReturn(List.of());
    when(release.manifestHash()).thenReturn(MANIFEST);
    when(release.manifestSchemaVersion()).thenReturn(1);
    when(release.requiredManifestAssetKeys()).thenReturn(List.of());
    when(release.artifactDigests()).thenReturn(List.of());
    when(release.commandDefinitions()).thenReturn(List.of());
    when(release.generationConfigRevision()).thenReturn(GENERATION);
    when(release.worldStartLocationEvidence()).thenReturn(null);
    return release;
  }

  private static RealmPolicyPublishedEvidence.PublishedSet published(
      TargetProof target, UUID sourceCommit, long publicationEpoch) {
    var published = mock(RealmPolicyPublishedEvidence.PublishedSet.class);
    when(published.target()).thenReturn(target);
    when(published.versionNumber()).thenReturn(1);
    when(published.sourceCommitId()).thenReturn(sourceCommit);
    when(published.publicationVersionStateEpoch()).thenReturn(publicationEpoch);
    when(published.publishedReleaseBundleRef()).thenReturn(RELEASE_REF);
    when(published.publishedReleaseBundleDigest()).thenReturn(RELEASE_DIGEST);
    when(published.publishWorkflowId()).thenReturn(WORKFLOW);
    when(published.manifestHash()).thenReturn(MANIFEST);
    return published;
  }

  private record ReadbackFixture(MockedStatic<GameDesignPublicationTerminalEvidence> decoder)
      implements AutoCloseable {
    @Override
    public void close() {
      decoder.close();
    }
  }
}
