package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt.Status;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindAttemptRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProofReader;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadProof;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.service.PublishedRealmAdmissionOwnerReadService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublishedRealmAdmissionOwnerReadServiceTest {
  private static final String NAMESPACE = "realm-ns";
  private static final long GAME_SESSION_TENANT_ID = 70123L;
  private static final long SOURCE_GAME_ROW_ID = 91L;
  private static final String SOURCE_GAME_TENANT_KEY = "gd-tenant-91";
  private static final String PROVENANCE_KIND = "RETAINED_GAME_V29";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REALM_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID PLAYABLE_NAMESPACE_ID =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID ATTEMPT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID HOLD_FENCE = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final String REQUEST_ID = "88888888-8888-4888-8888-888888888888";
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final long PUBLISHED_VERSION_ID = 44L;
  private static final int PUBLISHED_VERSION_NUMBER = 3;
  private static final long CATALOG_REVISION = 8L;
  private static final long GAME_INSTANCE_ID = 93L;
  private static final long ACTIVE_LIFECYCLE_EPOCH = 12L;
  private static final long POINTER_AUDIT_ID = 601L;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final InitialAdmissionBindCatalogRepository catalogRepository =
      mock(InitialAdmissionBindCatalogRepository.class);
  private final GameplayAdmissionPointerRepository pointerRepository =
      mock(GameplayAdmissionPointerRepository.class);
  private final GameplayAdmissionPointerEventRepository pointerEventRepository =
      mock(GameplayAdmissionPointerEventRepository.class);
  private final InitialAdmissionBindAttemptRepository attemptRepository =
      mock(InitialAdmissionBindAttemptRepository.class);
  private final InitialAdmissionBindOwnerProofReader ownerProofReader =
      mock(InitialAdmissionBindOwnerProofReader.class);
  private final PublishedRealmAdmissionOwnerReadService service =
      new PublishedRealmAdmissionOwnerReadService(
          catalogRepository,
          pointerRepository,
          pointerEventRepository,
          attemptRepository,
          ownerProofReader);

  @Test
  void composesPublishedPolicyCurrentPointerAuditAndCommittedPublishedAttempt() {
    PublishedRealmCatalogSnapshot snapshot = snapshot();
    PublishedRealmCatalogEntry entry = snapshot.entries().get(0);
    GameplayAdmissionPointer pointer = pointer();
    GameplayAdmissionPointerEvent audit = audit();
    InitialAdmissionBindAttempt attempt = attempt("V14_PUBLISHED");
    InitialAdmissionBindHoldBinding binding = binding(attempt);
    stubSnapshot(snapshot);
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(
            GAME_SESSION_TENANT_ID, "earth", "main"))
        .thenReturn(Optional.of(pointer));
    when(pointerEventRepository.findLatestByTenantIdAndWorldSlugAndRealmSlug(
            GAME_SESSION_TENANT_ID, "earth", "main"))
        .thenReturn(Optional.of(audit));
    when(attemptRepository.findByTenantAndRequestId(GAME_SESSION_TENANT_ID, REQUEST_ID))
        .thenReturn(Optional.of(attempt));
    when(ownerProofReader.read(binding)).thenReturn(ownerProof());

    PublishedRealmAdmissionOwnerReadProof proof = service.read(request());

    assertThat(proof.gameSessionTenantId()).isEqualTo(GAME_SESSION_TENANT_ID);
    assertThat(proof.canonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(proof.sourceGameRowId()).isEqualTo(SOURCE_GAME_ROW_ID);
    assertThat(proof.sourceGameTenantKey()).isEqualTo(SOURCE_GAME_TENANT_KEY);
    assertThat(proof.publishedPolicySet()).isEqualTo(snapshot.policySetEvidence());
    assertThat(proof.selectedPolicyEvidence()).isEqualTo(entry.policyEvidence());
    assertThat(proof.catalogRevision()).isEqualTo(CATALOG_REVISION);
    assertThat(proof.playableStateNamespaceId()).isEqualTo(PLAYABLE_NAMESPACE_ID);
    assertThat(proof.pointerVersion()).isEqualTo(1L);
    assertThat(proof.gameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(proof.publishedVersionId()).isEqualTo(PUBLISHED_VERSION_ID);
    assertThat(proof.activeLifecycleEpoch()).isEqualTo(ACTIVE_LIFECYCLE_EPOCH);
    assertThat(proof.gameTemplateId()).isEqualTo(55L);
    assertThat(proof.launchDescriptorId()).isEqualTo("launch-earth-v44");
    assertThat(proof.releaseBundleId()).isEqualTo(810L);
    assertThat(proof.versionStateEpoch()).isEqualTo(6L);
    assertThat(proof.initialAdmissionAttemptId()).isEqualTo(ATTEMPT_ID);
    assertThat(proof.pointerAuditId()).isEqualTo(POINTER_AUDIT_ID);
    verify(ownerProofReader).read(binding);
  }

  @Test
  void rejectsFixtureAttemptEvenWhenItsMutablePointerTupleMatches() {
    stubSnapshot(snapshot());
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(
            GAME_SESSION_TENANT_ID, "earth", "main"))
        .thenReturn(Optional.of(pointer()));
    when(pointerEventRepository.findLatestByTenantIdAndWorldSlugAndRealmSlug(
            GAME_SESSION_TENANT_ID, "earth", "main"))
        .thenReturn(Optional.of(audit()));
    when(attemptRepository.findByTenantAndRequestId(GAME_SESSION_TENANT_ID, REQUEST_ID))
        .thenReturn(Optional.of(attempt("V9_FIXTURE")));

    assertThatThrownBy(() -> service.read(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_ADMISSION_BIND_ATTEMPT_MISMATCH");

    verifyNoInteractions(ownerProofReader);
  }

  @Test
  void rejectsStaleOrLaterPointerVersionWithoutTreatingInitialBindAsCutoverProof() {
    stubSnapshot(snapshot());
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(
            GAME_SESSION_TENANT_ID, "earth", "main"))
        .thenReturn(Optional.of(pointer(2L)));

    assertThatThrownBy(() -> service.read(request(2L)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_ADMISSION_POINTER_STALE_OR_MISMATCHED");

    verifyNoInteractions(pointerEventRepository, attemptRepository, ownerProofReader);
  }

  @Test
  void rejectsMissingOwnerProofAfterAllLocalTuplesMatch() {
    PublishedRealmCatalogSnapshot snapshot = snapshot();
    InitialAdmissionBindAttempt attempt = attempt("V14_PUBLISHED");
    stubSnapshot(snapshot);
    when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(
            GAME_SESSION_TENANT_ID, "earth", "main"))
        .thenReturn(Optional.of(pointer()));
    when(pointerEventRepository.findLatestByTenantIdAndWorldSlugAndRealmSlug(
            GAME_SESSION_TENANT_ID, "earth", "main"))
        .thenReturn(Optional.of(audit()));
    when(attemptRepository.findByTenantAndRequestId(GAME_SESSION_TENANT_ID, REQUEST_ID))
        .thenReturn(Optional.of(attempt));
    when(ownerProofReader.read(binding(attempt)))
        .thenReturn(
            new InitialAdmissionBindOwnerProof(
                InitialAdmissionBindOwnerProof.Outcome.PENDING,
                HOLD_ID.toString(),
                HOLD_FENCE.toString(),
                GAME_SESSION_TENANT_ID,
                REALM_ID.toString(),
                PLAYABLE_NAMESPACE_ID.toString(),
                "SHARED",
                GAME_INSTANCE_ID,
                PUBLISHED_VERSION_ID,
                ACTIVE_LIFECYCLE_EPOCH,
                REQUEST_ID,
                REQUEST_DIGEST,
                true,
                CATALOG_REVISION,
                ATTEMPT_ID.toString(),
                Long.toString(POINTER_AUDIT_ID),
                1L,
                REQUEST_DIGEST,
                false));

    assertThatThrownBy(() -> service.read(request()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_ADMISSION_OWNER_PROOF_MISSING_OR_MISMATCHED");
  }

  private void stubSnapshot(PublishedRealmCatalogSnapshot snapshot) {
    when(catalogRepository.findPublishedSnapshot(
            NAMESPACE, GAME_SESSION_TENANT_ID, CATALOG_REVISION))
        .thenReturn(Optional.of(snapshot));
  }

  private static PublishedRealmAdmissionOwnerReadRequest request() {
    return request(1L);
  }

  private static PublishedRealmAdmissionOwnerReadRequest request(long pointerVersion) {
    return new PublishedRealmAdmissionOwnerReadRequest(
        NAMESPACE,
        CANONICAL_TENANT_ID,
        GAME_SESSION_TENANT_ID,
        "earth",
        "main",
        CATALOG_REVISION,
        pointerVersion);
  }

  private static PublishedRealmCatalogSnapshot snapshot() {
    PublishedRealmEntryPolicySetEvidence policySet = policySet();
    PublishedRealmCatalogEntry entry =
        new PublishedRealmCatalogEntry(
            GAME_SESSION_TENANT_ID,
            CATALOG_REVISION,
            REALM_ID,
            PLAYABLE_NAMESPACE_ID,
            NamespaceResolution.RESOLVED,
            policySet.policies().get(0));
    return new PublishedRealmCatalogSnapshot(
        GAME_SESSION_TENANT_ID,
        NAMESPACE,
        CANONICAL_TENANT_ID,
        SOURCE_GAME_ROW_ID,
        SOURCE_GAME_TENANT_KEY,
        PROVENANCE_KIND,
        CATALOG_REVISION,
        policySet,
        List.of(entry),
        Instant.parse("2026-10-02T00:00:00Z"));
  }

  private static PublishedRealmEntryPolicySetEvidence policySet() {
    String workflow = "publish:published-admission-owner-read-test";
    String manifest = "manifest-published-admission-owner-read-test";
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, PUBLISHED_VERSION_ID, workflow, manifest, JSON);
    String policyJson =
        "{\"schemaVersion\":1,\"worldSlug\":\"earth\",\"worldDisplayName\":\"Earth\","
            + "\"realmSlug\":\"main\",\"realmDisplayName\":\"Main\",\"visible\":true,"
            + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
            + "\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    PublishedRealmEntryPolicyEvidence evidence =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            CANONICAL_TENANT_ID,
            PROVENANCE_KIND,
            SOURCE_GAME_ROW_ID,
            SOURCE_GAME_TENANT_KEY,
            PUBLISHED_VERSION_ID,
            PUBLISHED_VERSION_NUMBER,
            301L,
            releaseIdentity,
            workflow,
            manifest,
            RealmEntryPolicy.parse(policyJson, JSON),
            JSON);
    return PublishedRealmEntryPolicySetEvidence.create(
        CANONICAL_TENANT_ID,
        PUBLISHED_VERSION_ID,
        PUBLISHED_VERSION_NUMBER,
        releaseIdentity,
        workflow,
        manifest,
        List.of(evidence),
        JSON);
  }

  private static GameplayAdmissionPointer pointer() {
    return pointer(1L);
  }

  private static GameplayAdmissionPointer pointer(long pointerVersion) {
    GameplayAdmissionPointer pointer = new GameplayAdmissionPointer();
    pointer.setId(501L);
    pointer.setWorldSlug("earth");
    pointer.setWorldDisplayName("Earth");
    pointer.setRealmSlug("main");
    pointer.setRealmDisplayName("Main");
    pointer.setTenantId(GAME_SESSION_TENANT_ID);
    pointer.setGameInstanceId(GAME_INSTANCE_ID);
    pointer.setPointerVersion(pointerVersion);
    pointer.setCatalogRevision(CATALOG_REVISION);
    pointer.setRealmId(REALM_ID);
    pointer.setPlayableStateNamespaceId(PLAYABLE_NAMESPACE_ID);
    pointer.setVisible(true);
    pointer.setPublicProductionRealm(true);
    pointer.setRequiresCharacterSelection(true);
    pointer.setStateScope("SHARED");
    pointer.setCharacterCreationPolicy("PRESEEDED_ONLY");
    pointer.setLastUpdatedBy("game-session-initial-admission-bind");
    pointer.setLastUpdateReason("initial admission pointer bind");
    return pointer;
  }

  private static GameplayAdmissionPointerEvent audit() {
    GameplayAdmissionPointerEvent audit = new GameplayAdmissionPointerEvent();
    audit.setId(POINTER_AUDIT_ID);
    audit.setWorldSlug("earth");
    audit.setWorldDisplayName("Earth");
    audit.setRealmSlug("main");
    audit.setRealmDisplayName("Main");
    audit.setTenantId(GAME_SESSION_TENANT_ID);
    audit.setGameInstanceId(GAME_INSTANCE_ID);
    audit.setPointerVersion(1L);
    audit.setCatalogRevision(CATALOG_REVISION);
    audit.setRealmId(REALM_ID);
    audit.setPlayableStateNamespaceId(PLAYABLE_NAMESPACE_ID);
    audit.setVisible(true);
    audit.setPublicProductionRealm(true);
    audit.setRequiresCharacterSelection(true);
    audit.setStateScope("SHARED");
    audit.setCharacterCreationPolicy("PRESEEDED_ONLY");
    audit.setActorPrincipal("game-session-initial-admission-bind");
    audit.setReason("initial admission pointer bind");
    audit.setControlPlaneRequestId(REQUEST_ID);
    audit.setOccurredAt(Instant.parse("2026-10-02T00:01:00Z"));
    return audit;
  }

  private static InitialAdmissionBindAttempt attempt(String catalogSourceKind) {
    Instant createdAt = Instant.parse("2026-10-02T00:00:00Z");
    return new InitialAdmissionBindAttempt(
        ATTEMPT_ID,
        GAME_SESSION_TENANT_ID,
        REQUEST_ID,
        REQUEST_DIGEST,
        REALM_ID,
        PLAYABLE_NAMESPACE_ID,
        "SHARED",
        true,
        CATALOG_REVISION,
        GAME_INSTANCE_ID,
        PUBLISHED_VERSION_ID,
        ACTIVE_LIFECYCLE_EPOCH,
        HOLD_ID,
        HOLD_FENCE,
        Status.COMMITTED,
        501L,
        POINTER_AUDIT_ID,
        createdAt,
        createdAt,
        createdAt,
        catalogSourceKind,
        NAMESPACE,
        CANONICAL_TENANT_ID,
        55L,
        "launch-earth-v44",
        810L,
        "gd://tenant/44/release/810",
        6L);
  }

  private static InitialAdmissionBindHoldBinding binding(InitialAdmissionBindAttempt attempt) {
    return new InitialAdmissionBindHoldBinding(
        attempt.holdId().toString(),
        attempt.holdFence().toString(),
        attempt.tenantId(),
        attempt.realmId().toString(),
        attempt.playableStateNamespaceId().toString(),
        attempt.playableStateScope(),
        attempt.gameInstanceId(),
        attempt.versionId(),
        attempt.activeLifecycleEpoch(),
        attempt.initialAdmissionRequestId(),
        attempt.requestDigest(),
        attempt.expectedNoPriorPointer(),
        attempt.catalogRevision());
  }

  private static InitialAdmissionBindOwnerProof ownerProof() {
    return new InitialAdmissionBindOwnerProof(
        InitialAdmissionBindOwnerProof.Outcome.COMMITTED,
        HOLD_ID.toString(),
        HOLD_FENCE.toString(),
        GAME_SESSION_TENANT_ID,
        REALM_ID.toString(),
        PLAYABLE_NAMESPACE_ID.toString(),
        "SHARED",
        GAME_INSTANCE_ID,
        PUBLISHED_VERSION_ID,
        ACTIVE_LIFECYCLE_EPOCH,
        REQUEST_ID,
        REQUEST_DIGEST,
        true,
        CATALOG_REVISION,
        ATTEMPT_ID.toString(),
        Long.toString(POINTER_AUDIT_ID),
        1L,
        REQUEST_DIGEST,
        false);
  }
}
