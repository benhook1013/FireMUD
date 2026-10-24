package net.firedevops.firemud.entitymanagement.charactercreate;

import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection.AuthoredCore;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSourceClient.AuthenticatedSource;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRecoveryService;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy;
import net.firedevops.firemud.entitymanagement.service.impl.CanonicalGameplayRosterGrpcAdapter;
import net.firedevops.firemud.entitymanagement.service.impl.CanonicalGameplayRosterRecoveryGrpcAdapter;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture.WireFixture;
import net.firedevops.firemud.entitymanagement.testing.ControlledSelectedSourceIntakeFixtureBuilder;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Controlled PostgreSQL composition for the unregistered created-roster owner. Its upstream
 * publication/source client is a real configured mTLS client; Account, Game Session creation
 * protection, and World evidence used only by {@link #createActor} are controlled proof inputs.
 */
public final class ControlledPlayerCreatedOwnerStoreFixture {
  private final WireFixture wireFixture;
  private final EntityPlayerCreatedRosterSourceClient sourceClient;
  private final EntityEmptySelectedSourceIntakeRepository sourceIntakes;
  private final EntityCharacterCreateRepository characterCreateRepository;
  private final CanonicalGameplayRosterRetentionRepository retentionRepository;
  private final EntityPlayerCreatedRosterSnapshotRepository snapshotRepository;
  private final EntityPlayerCreatedRosterService rosterService;
  private final CanonicalGameplayRosterRecoveryService recoveryService;
  private final CanonicalGameplayRosterGrpcAdapter rosterAdapter;
  private final CanonicalGameplayRosterRecoveryGrpcAdapter recoveryAdapter;
  private EntityEmptySelectedSourceIntakeReceipt sourceReceipt;

  public ControlledPlayerCreatedOwnerStoreFixture(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      String namespace,
      WireFixture wireFixture,
      EntityPlayerCreatedRosterSourceClient sourceClient,
      GameSessionCanonicalGameplayRosterOwnerReadClient originalOwnerClient,
      Clock clock,
      CanonicalGameplayRosterRetentionPolicy retentionPolicy) {
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    Objects.requireNonNull(namespace, "namespace");
    this.wireFixture = Objects.requireNonNull(wireFixture, "wireFixture");
    this.sourceClient = Objects.requireNonNull(sourceClient, "sourceClient");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(retentionPolicy, "retentionPolicy");
    Objects.requireNonNull(originalOwnerClient, "originalOwnerClient");
    if (!namespace.equals(wireFixture.request().getTargetNamespace())) {
      throw new IllegalArgumentException(
          "Controlled source namespace differs from owner namespace");
    }

    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setReadOnly(false);
    TransactionOperations transactionOperations = transactions;
    sourceIntakes = new EntityEmptySelectedSourceIntakeRepository(dsl);
    characterCreateRepository = new EntityCharacterCreateRepository(dsl, clock);
    retentionRepository = new CanonicalGameplayRosterRetentionRepository(dsl);
    snapshotRepository = new EntityPlayerCreatedRosterSnapshotRepository(dsl, transactionManager);
    rosterService =
        new EntityPlayerCreatedRosterService(
            namespace,
            sourceClient,
            originalOwnerClient,
            snapshotRepository,
            retentionRepository,
            transactionOperations);
    recoveryService =
        new CanonicalGameplayRosterRecoveryService(
            retentionRepository, originalOwnerClient, transactionOperations, retentionPolicy);
    rosterAdapter =
        new CanonicalGameplayRosterGrpcAdapter(
            null, null, rosterService, new SimpleMeterRegistry());
    recoveryAdapter = new CanonicalGameplayRosterRecoveryGrpcAdapter(recoveryService);
  }

  /** Performs a fresh configured mTLS read; only the returned client capability is accepted. */
  public AuthenticatedSource readAuthenticatedSource() {
    AuthenticatedSource source = sourceClient.read(wireFixture.request());
    if (!source.response().equals(wireFixture.response())) {
      throw new IllegalStateException("Configured controlled source returned different evidence");
    }
    return source;
  }

  /**
   * Uses the actual Entity creation writer to allocate one PLAYER_CREATED actor and persist its
   * complete COMMITTED terminal. The owner protection inputs here are controlled evidence.
   */
  public synchronized EntityCharacterCreateTerminal createActor(
      UUID account, UUID operation, String displayName) throws Exception {
    return createActor(account, operation, displayName, false);
  }

  /** Fills the bounded known opaque proof fields while retaining the actual writer and bindings. */
  public synchronized EntityCharacterCreateTerminal createActor(
      UUID account, UUID operation, String displayName, boolean maximumKnownOpaqueFields)
      throws Exception {
    EntityCharacterCreateBindings.id(Objects.requireNonNull(account, "account").toString());
    EntityCharacterCreateBindings.id(Objects.requireNonNull(operation, "operation").toString());
    AuthenticatedSource source = readAuthenticatedSource();
    var projection = source.authored().projections().getFirst();
    String authoredName = new AuthoredCore(projection.core().canonicalJson()).displayName();
    if (!Objects.equals(authoredName, displayName)) {
      throw new IllegalArgumentException("Display name must match the authenticated authored core");
    }
    DraftCommitBinding selected =
        GameDesignPublicationOperationBinding.fromStored(
                source.publishedPolicySet().operationBytes())
            .account()
            .input()
            .selection()
            .selectedCommit();
    if (!selected.target().canonicalTenantId().equals(source.target().tenantUuid())
        || !selected.target().canonicalVersionId().equals(source.target().canonicalVersionUuid())) {
      throw new IllegalStateException("Controlled selected binding differs from source target");
    }
    if (sourceReceipt == null) {
      var intake =
          ControlledSelectedSourceIntakeFixtureBuilder.create(
              "1", selected, source.response().getRequest().getTargetNamespace());
      var inputs = intake.inputs();
      String digest =
          EntityEmptySelectedSourceIntakeReceipt.requestDigest(
              inputs.authorizationBinding().targetNamespace(),
              inputs.authorizationBinding(),
              inputs.worldInventoryReadEvidence().request().freezeEvidence());
      sourceReceipt = sourceIntakes.retainFresh(inputs, digest);
    }
    EntityCharacterCreateProofs proof =
        createProof(sourceReceipt, account, operation, source, maximumKnownOpaqueFields);
    return characterCreateRepository.settle(proof, null);
  }

  public CanonicalGameplayRosterGrpcAdapter rosterAdapter() {
    return rosterAdapter;
  }

  public CanonicalGameplayRosterRecoveryGrpcAdapter recoveryAdapter() {
    return recoveryAdapter;
  }

  public EntityPlayerCreatedRosterService rosterService() {
    return rosterService;
  }

  public CanonicalGameplayRosterRecoveryService recoveryService() {
    return recoveryService;
  }

  public CanonicalGameplayRosterRetentionRepository retentionRepository() {
    return retentionRepository;
  }

  public EntityPlayerCreatedRosterSnapshotRepository snapshotRepository() {
    return snapshotRepository;
  }

  public WireFixture wireFixture() {
    return wireFixture;
  }

  private static EntityCharacterCreateProofs createProof(
      EntityEmptySelectedSourceIntakeReceipt entitySource,
      UUID accountId,
      UUID operationId,
      AuthenticatedSource rosterSource,
      boolean maximumKnownOpaqueFields)
      throws Exception {
    CharacterCreateOperatorAction action =
        rosterSourceAction(entitySource, accountId, rosterSource);
    byte[] actionBytes = CharacterCreateOperatorActionCodec.encode(action);
    Instant readAt = action.expectedVersion().evaluatedAt().plusSeconds(1);
    var original =
        net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse.newBuilder()
            .setTargetNamespace(entitySource.targetNamespace())
            .setOperationId(operationId.toString())
            .setCreateCharacterRequestId(UUID.randomUUID().toString())
            .setConnectScopeId("Controlled.Owner.Scope-Exact")
            .setAuthenticatedAccountId(accountId.toString())
            .setPurpose("CHARACTER_CREATE")
            .setOriginalAction(ByteString.copyFrom(actionBytes))
            .setMutationDigest(CharacterCreateOperatorActionCodec.mutationDigest(action))
            .setOriginalSourceReadback(
                maximumKnownOpaqueFields
                    ? opaque(32 * 1024 * 1024, 301)
                    : ByteString.copyFromUtf8("controlled-upstream-source-readback"))
            .build();
    ReadOriginalCharacterCreateRequest reference =
        ReadOriginalCharacterCreateRequest.newBuilder()
            .setTargetNamespace(entitySource.targetNamespace())
            .setOperationId(operationId.toString())
            .setMutationDigest(original.getMutationDigest())
            .build();
    CharacterCreateProtectionReference accountProtection = protectionReference();
    CharacterCreateProtectionReference gameSessionProtection = protectionReference();
    CharacterCreateProtectionReference worldProtection = protectionReference();
    var request =
        CommitProtectedCharacterCreateRequest.newBuilder()
            .setOriginal(reference)
            .setAccountProtection(accountProtection)
            .setGameSessionProtection(gameSessionProtection)
            .setWorldProtection(worldProtection)
            .build();
    var account =
        ReadOriginalCharacterCreateProtectionResponse.newBuilder()
            .setOriginal(original)
            .setAccountProtectionId(accountProtection.getProtectionId())
            .setAccountProtectionFence(accountProtection.getProtectionFence())
            .setAcquiredAt(readAt.toString())
            .setOriginalMembershipSnapshot(
                maximumKnownOpaqueFields
                    ? opaque(1024 * 1024, 302)
                    : ByteString.copyFromUtf8("controlled-membership"))
            .setOriginalEntitlementSnapshot(
                maximumKnownOpaqueFields
                    ? opaque(1024 * 1024, 303)
                    : ByteString.copyFromUtf8("controlled-entitlement"))
            .build();
    var gameSession =
        new GameSessionCharacterCreateProtectionReader.Readback(
            account,
            gameSessionProtection,
            readAt.plusSeconds(1).toString(),
            maximumKnownOpaqueFields
                ? opaque(48 * 1024 * 1024, 304)
                : ByteString.copyFromUtf8("controlled-Game-Session-protection-readback"));
    var world =
        WorldCharacterCreateLifecycleProtection.newBuilder()
            .setAccountProtection(account)
            .setWorldProtectionId(worldProtection.getProtectionId())
            .setWorldProtectionFence(worldProtection.getProtectionFence())
            .setAcquiredAt(readAt.plusSeconds(2).toString())
            .setOriginalLifecycleEvidence(
                ByteString.copyFrom(
                    creationLifecycleEvidence(rosterSource.world(), operationId).canonicalBytes()))
            .build();
    return new EntityCharacterCreateProofs(
        request, original, account, gameSession, world, entitySource);
  }

  /** Uses the same seeded chunked high-entropy construction as the Common transport vector. */
  private static ByteString opaque(int size, long seed) {
    var random = new java.util.Random(seed);
    byte[] chunk = new byte[64 * 1024];
    var output = ByteString.newOutput(chunk.length);
    for (int remaining = size; remaining > 0; ) {
      int count = Math.min(remaining, chunk.length);
      random.nextBytes(chunk);
      output.write(chunk, 0, count);
      remaining -= count;
    }
    return output.toByteString();
  }

  /**
   * Rebinds only the controlled World lifecycle read correlation to the creation operation. The
   * authenticated source's target, complete launch binding, and lifecycle facts remain intact.
   */
  private static WorldCanonicalInstanceLifecycleEvidence creationLifecycleEvidence(
      WorldCanonicalInstanceLifecycleEvidence source, UUID operationId) {
    var sourceRequest = source.request();
    var creationRequest =
        new WorldCanonicalInstanceLifecycleEvidence.Request(
            sourceRequest.schemaVersion(),
            operationId,
            sourceRequest.targetNamespace(),
            sourceRequest.canonicalTenantId(),
            sourceRequest.worldSlug(),
            sourceRequest.canonicalGameInstanceId(),
            sourceRequest.playableStateNamespaceId(),
            sourceRequest.playableStateScope(),
            sourceRequest.publicProduction(),
            sourceRequest.controlPlaneRequestId(),
            sourceRequest.canonicalVersionId(),
            sourceRequest.expectedDescriptorRequestDigest(),
            sourceRequest.expectedDescriptorResultDigest(),
            sourceRequest.expectedReleaseAttestationDigest());
    return new WorldCanonicalInstanceLifecycleEvidence(
        creationRequest,
        source.launchBinding(),
        source.startLocation(),
        source.runtimeRoomInstanceId(),
        source.lifecycleStatus(),
        source.lifecycleEpoch(),
        source.rowVersion(),
        source.captureId(),
        source.graphSha256(),
        source.preparationInputDigest(),
        source.operationalRegionAssignments());
  }

  private static CharacterCreateProtectionReference protectionReference() {
    return CharacterCreateProtectionReference.newBuilder()
        .setProtectionId(UUID.randomUUID().toString())
        .setProtectionFence(1L)
        .build();
  }

  public static CharacterCreateOperatorAction rosterSourceAction(
      EntityEmptySelectedSourceIntakeReceipt entitySource,
      UUID accountId,
      AuthenticatedSource source) {
    var target = source.target();
    var request = source.response().getRequest();
    var catalog = source.response().getCatalog();
    var pointer = source.response().getPointer();
    var projection = source.authored().projections().getFirst();
    DraftCommitBinding sourceSelected =
        GameDesignPublicationOperationBinding.fromStored(
                source.publishedPolicySet().operationBytes())
            .account()
            .input()
            .selection()
            .selectedCommit();
    if (!entitySource.targetNamespace().equals(request.getTargetNamespace())
        || !entitySource.canonicalTenantId().equals(target.tenantUuid())
        || !entitySource.canonicalVersionId().equals(target.canonicalVersionUuid())
        || !entitySource.selectedCommitId().equals(projection.selectedCommitId())
        || !sourceSelected.commitId().equals(projection.selectedCommitId())
        || !sourceSelected.digest().equals(projection.selectedCommitDigest())
        || !entitySource.authorizationBinding().selected().equals(sourceSelected)) {
      throw new IllegalArgumentException(
          "Controlled Entity writer fixture does not match authenticated source binding");
    }
    Instant pointerUpdatedAt = EntityCharacterCreateBindings.instant(pointer.getUpdatedAt());
    return new CharacterCreateOperatorAction(
        new CharacterCreateOperatorAction.Scope(
            request.getTargetNamespace(),
            target.tenantUuid().toString(),
            target.playableStateNamespaceId().toString(),
            CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
        new CharacterCreateOperatorAction.Target(
            accountId.toString(),
            target.realmUuid().toString(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid().toString(),
            target.canonicalVersionUuid().toString()),
        new CharacterCreateOperatorAction.ExpectedVersion(
            Long.toString(target.catalogRevision()),
            Long.toString(target.pointerVersion()),
            Long.toString(target.activeWorldEpoch()),
            pointerUpdatedAt.plusSeconds(1),
            pointerUpdatedAt.plusSeconds(301)),
        new CharacterCreateOperatorAction.Mutation(
            "CHARACTER_CREATE",
            "PLAYER_CREATED",
            new CharacterCreateOperatorAction.Catalog(
                target.tenantUuid().toString(),
                catalog.getTenantSlug(),
                target.worldSlug(),
                catalog.getWorldDisplayName(),
                target.realmUuid().toString(),
                target.realmSlug(),
                catalog.getRealmDisplayName(),
                catalog.getVisible(),
                catalog.getPublicProduction(),
                catalog.getStateScope(),
                target.playableStateNamespaceId().toString(),
                catalog.getCharacterCreationPolicy(),
                Long.toString(target.catalogRevision())),
            new CharacterCreateOperatorAction.Pointer(
                target.tenantUuid().toString(),
                target.realmUuid().toString(),
                target.worldSlug(),
                target.realmSlug(),
                pointer.getAdmissionState(),
                target.gameInstanceUuid().toString(),
                Long.toString(target.pointerVersion()),
                Long.toString(target.catalogRevision()),
                pointerUpdatedAt),
            authoredSource(projection),
            "{}"),
        CharacterCreateOperatorAction.AuditReason.ABSENT);
  }

  private static CharacterCreateOperatorAction.AuthoredSource authoredSource(
      AuthoredInitialActorCoreProjection projection) {
    var policy = projection.policy();
    return new CharacterCreateOperatorAction.AuthoredSource(
        AuthoredInitialActorCoreProjection.SCHEMA,
        projection.bindingId().toString(),
        projection.bindingDigest(),
        projection.canonicalTenantId().toString(),
        projection.canonicalVersionId().toString(),
        projection.worldSourceOperationId().toString(),
        projection.worldSourceEvidenceDigest(),
        projection.worldSlug(),
        projection.publicationWorkflowId(),
        projection.selectedCommitId().toString(),
        projection.selectedCommitDigest(),
        new CharacterCreateOperatorAction.Policy(
            policy.commitId().toString(),
            policy.revisionId().toString(),
            policy.logicalRevisionId(),
            policy.canonicalJson(),
            policy.contentDigest()),
        new CharacterCreateOperatorAction.Core(projection.core().canonicalJson()),
        new CharacterCreateOperatorAction.Descriptor(
            projection.descriptor().canonicalJson(), projection.descriptor().digest()),
        authoredRule(projection.disposition()),
        authoredRule(projection.safeFeedback()));
  }

  private static CharacterCreateOperatorAction.Rule authoredRule(
      AuthoredInitialActorCoreProjection.RuleDefinition rule) {
    return new CharacterCreateOperatorAction.Rule(
        rule.key(),
        rule.definitionJson(),
        rule.definitionDigest(),
        rule.sourceCommitId().toString(),
        rule.sourceCommitDigest(),
        rule.revisionId().toString(),
        rule.revisionOrder());
  }
}
