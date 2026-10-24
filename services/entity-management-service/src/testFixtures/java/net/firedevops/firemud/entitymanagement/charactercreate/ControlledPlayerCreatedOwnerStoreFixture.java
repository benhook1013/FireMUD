package net.firedevops.firemud.entitymanagement.charactercreate;

import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.gamedesign.AssetSnapshot;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection.AuthoredCore;
import net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSnapshot;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Freeze;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicAccountOrder;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SourceModel;
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
          ControlledIntakeBuilder.create(
              "1", selected, source.response().getRequest().getTargetNamespace());
      var inputs = intake.inputs();
      String digest =
          EntityEmptySelectedSourceIntakeReceipt.requestDigest(
              inputs.authorizationBinding().targetNamespace(),
              inputs.authorizationBinding(),
              inputs.worldInventoryReadEvidence().request().freezeEvidence());
      sourceReceipt = sourceIntakes.retainFresh(inputs, digest);
    }
    EntityCharacterCreateProofs proof = createProof(sourceReceipt, account, operation, source);
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

  public EntityCharacterCreateRepository characterCreateRepository() {
    return characterCreateRepository;
  }

  public WireFixture wireFixture() {
    return wireFixture;
  }

  private static EntityCharacterCreateProofs createProof(
      EntityEmptySelectedSourceIntakeReceipt entitySource,
      UUID accountId,
      UUID operationId,
      AuthenticatedSource rosterSource)
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
                ByteString.copyFromUtf8("controlled-upstream-source-readback"))
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
            .setOriginalMembershipSnapshot(ByteString.copyFromUtf8("controlled-membership"))
            .setOriginalEntitlementSnapshot(ByteString.copyFromUtf8("controlled-entitlement"))
            .build();
    var gameSession =
        new GameSessionCharacterCreateProtectionReader.Readback(
            account,
            gameSessionProtection,
            readAt.plusSeconds(1).toString(),
            ByteString.copyFromUtf8("controlled-Game-Session-protection-readback"));
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

  private static CharacterCreateOperatorAction rosterSourceAction(
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

  /** Small typed fixture builder for a controlled fresh Entity owner source-intake receipt. */
  private static final class ControlledIntakeBuilder {
    static final UUID ACTOR = id("99999999-9999-4999-8999-999999999999");

    private static final UUID OPERATION = id("55555555-5555-4555-8555-555555555555");
    private static final UUID FENCE = id("66666666-6666-4666-8666-666666666666");
    private static final UUID INTAKE_REQUEST = id("77777777-7777-4777-8777-777777777777");
    private static final UUID GENESIS_RECEIPT = id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private ControlledIntakeBuilder() {}

    public static Fixture create(
        String sourceEpoch, DraftCommitBinding selected, String namespace) {
      Objects.requireNonNull(selected, "selected");
      Objects.requireNonNull(namespace, "namespace");
      var target = selected.target();
      var scope =
          new net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope(
              Owner.ENTITY_MANAGEMENT,
              namespace,
              OPERATION,
              FENCE,
              INTAKE_REQUEST,
              ACTOR,
              selected);
      TemplateConfigSourceSnapshot templateSnapshot = templateSnapshot(selected, sourceEpoch);
      SelectedOwnerIntakeAuthorizationBinding authorization =
          new SelectedOwnerIntakeAuthorizationBinding(
              sourceContent(scope, templateSnapshot), accountSources(target));
      WorldSelectedDraftPublicationFreezeEvidence freeze = freezeEvidence(selected, namespace);
      var inventory =
          WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
              freeze, publicInventory(freeze));
      var worldRequest =
          SelectedOwnerWorldInventoryReadEvidence.create(namespace, authorization, freeze);
      var worldEvidence = new SelectedOwnerWorldInventoryReadEvidence(worldRequest, inventory);
      return new Fixture(
          authorization,
          freeze,
          worldEvidence,
          new SelectedOwnerEmptySourceInputs(authorization, worldEvidence));
    }

    private static TemplateConfigSourceSnapshot templateSnapshot(
        DraftCommitBinding selected, String sourceEpoch) {
      var declaration =
          EntityAuthoredSourceInventoryDeclaration.parse(
              "{\"schema\":\"entity-authored-source-inventory/v1\","
                  + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
                  + "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],"
                  + "\"ARCHETYPE_CONSTRAINTS\":[],\"ARCHETYPE_ROOTS\":[],"
                  + "\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
                  + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],"
                  + "\"CRAFTING_INGREDIENT_BINDINGS\":[],\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],"
                  + "\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
                  + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],"
                  + "\"EQUIPMENT_OCCUPANCY_RULES\":[],\"EQUIPMENT_SLOT_GROUPS\":[],"
                  + "\"EQUIPMENT_SLOT_ROOTS\":[],\"INBOUND_LOOT_BINDINGS\":[],"
                  + "\"ITEM_TEMPLATE_ROOTS\":[],\"LOOT_ITEM_MAPPINGS\":[],"
                  + "\"LOOT_TABLE_ROOTS\":[],\"NPC_TEMPLATE_ROOTS\":[],"
                  + "\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]}}");
      String declarationPayload =
          TemplateConfigSourceValues.ownerInventoryPayload(Owner.ENTITY_MANAGEMENT, declaration);
      DraftCommitBinding authored = authoredBinding(declarationPayload, selected.target());
      TemplateConfigOwnerSourceInventoryDeclaration ownerDeclaration =
          TemplateConfigSourceValues.mutations(authored).stream()
              .filter(
                  mutation ->
                      mutation.operation()
                          == TemplateConfigSourceValues.OperationKind
                              .DECLARE_OWNER_SOURCE_INVENTORY)
              .map(TemplateConfigSourceValues.Mutation::ownerInventoryDeclaration)
              .findFirst()
              .orElseThrow();
      return new TemplateConfigSourceSnapshot(
          selected,
          sourceEpoch,
          authored.commitId(),
          GENESIS_RECEIPT,
          List.of(),
          List.of(ownerDeclaration));
    }

    private static DraftCommitBinding authoredBinding(String payload, TargetProof target) {
      return DraftCommitBinding.create(
          target,
          id("51515151-5151-4151-8151-515151515151"),
          id("52525252-5252-4252-8252-525252525252"),
          "base-commit-0",
          List.of(
              new RevisionPayload(
                  "0",
                  id("53535353-5353-4353-8353-535353535353"),
                  Owner.GAME_DESIGN_CONTROL_PLANE,
                  payload)),
          List.of(
              new DraftCommitBinding.AffectedUnit(
                  Owner.GAME_DESIGN_CONTROL_PLANE,
                  TemplateConfigSourceValues.SCOPE,
                  target.canonicalVersionId().toString(),
                  TemplateConfigSourceValues.SCOPE,
                  TemplateConfigSourceValues.SCOPE_ID,
                  "0")));
    }

    private static SelectedOwnerIntakeSourceContent sourceContent(
        net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope scope,
        TemplateConfigSourceSnapshot templateSnapshot) {
      var out = new ByteArrayOutputStream();
      frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
      frame(out, scope.canonicalBytes());
      frame(out, scope.digest());
      for (String family :
          List.of(
              "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
        byte[] snapshot =
            "TEMPLATE_CONFIG".equals(family)
                ? templateSnapshot.canonicalBytes()
                : sourceSnapshot(family, scope.selected());
        frame(out, family);
        frame(out, snapshot);
        frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
      }
      byte[] bytes = out.toByteArray();
      return SelectedOwnerIntakeSourceContent.fromStored(
          bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
    }

    private static byte[] sourceSnapshot(String family, DraftCommitBinding selected) {
      // These values exercise exact stored-byte decoding only; they are synthetic, not owner proof.
      return switch (family) {
        case "COMMAND" ->
            new CommandSnapshot(selected, "0", null, "sha256:" + "0".repeat(64), List.of())
                .canonicalBytes();
        case "REALM_POLICY" -> new RealmPolicySnapshot(selected, "0", List.of()).canonicalBytes();
        case "ASSET" ->
            new AssetSnapshot(selected, "0", null, GENESIS_RECEIPT, List.of()).canonicalBytes();
        case "GAMEPLAY_RULE" ->
            new GameplayRuleSelectedSource(
                    GameplayRuleManifest.canonical(
                        Map.of(
                            "schema",
                            "game-design-gameplay-rule-source-snapshot/v1",
                            "bindingJson",
                            selected.canonicalJson(),
                            "bindingDigest",
                            selected.digest(),
                            "sourceEpoch",
                            "0",
                            "inheritedCommitId",
                            "",
                            "genesisReceiptId",
                            GENESIS_RECEIPT.toString(),
                            "manifestJson",
                            GameplayRuleManifest.explicitEmpty().canonicalJson(),
                            "entries",
                            List.of())))
                .canonicalBytes();
        case "BRANDING" ->
            new BrandingSourceSnapshot(selected, "0", null, GENESIS_RECEIPT, List.of())
                .canonicalBytes();
        default ->
            throw new IllegalArgumentException("Unsupported source family fixture: " + family);
      };
    }

    private static List<SourceEvidence> accountSources(TargetProof target) {
      String tenant = target.canonicalTenantId().toString();
      return List.of(
          source(SourceKind.TENANT, tenant, "tenant"),
          source(SourceKind.ACCOUNT, ACTOR.toString(), "account"),
          source(SourceKind.MEMBERSHIP, ACTOR + "/" + tenant, "membership"));
    }

    private static SourceEvidence source(SourceKind kind, String scope, String marker) {
      return new SourceEvidence(
          kind, scope, null, "1", null, null, marker.getBytes(StandardCharsets.UTF_8));
    }

    private static WorldSelectedDraftPublicationFreezeEvidence freezeEvidence(
        DraftCommitBinding selected, String namespace) {
      var target = selected.target();
      var selection =
          AuthoredDraftPublishSelectionBinding.capture(
              new PublishIntent(
                  target.canonicalTenantId(),
                  target.canonicalVersionId(),
                  "publication-request",
                  "9",
                  "synthetic selected-owner intake fixture",
                  selected.requestId(),
                  selected.commitId(),
                  selected.digest()),
              selected.target(),
              selected,
              new VisibilityFence(
                  selected.target(),
                  selected.requestId(),
                  selected.commitId(),
                  selected.digest(),
                  "[]",
                  OffsetDateTime.parse("2026-10-01T00:00:00Z")));
      var account =
          new AccountPublicationAuthorizationBinding(
              id("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
              id("ffffffff-ffff-4fff-8fff-ffffffffffff"),
              new AccountPublicationAuthorizationBinding.PreallocationInput(ACTOR, selection),
              List.of(source(SourceKind.ACCOUNT, ACTOR.toString(), "account-publication")));
      var request =
          WorldSelectedDraftPublicationFreezeEvidence.Request.create(
              namespace,
              target.canonicalTenantId(),
              target.canonicalVersionId(),
              selection.intent().publishRequestId(),
              9,
              selection.digest().substring("sha256:".length()),
              account);
      var acknowledgement =
          new Acknowledgement(
              request,
              id("12121212-1212-4212-8212-121212121212"),
              9,
              id("13131313-1313-4313-8313-131313131313"),
              OwnerFreezePhase.FROZEN,
              selected.commitId().toString(),
              "a".repeat(64),
              4);
      return WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
          request, WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
    }

    private static PublicEvidence publicInventory(
        WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
      var request = freezeEvidence.request();
      var target = request.accountBinding().input().selection().selectedCommit().target();
      var acknowledgement = freezeEvidence.acknowledgement();
      var account = request.accountBinding();
      var selection = account.input().selection();
      var selected = selection.selectedCommit();
      var sourceModel =
          new SourceModel(
              WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL,
              WorldSelectedPublicationArtifactInventoryEvidence
                  .INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION,
              "sha256:" + "4".repeat(64),
              "sha256:" + "5".repeat(64),
              List.of(
                  new FamilyCount("REGION", 1),
                  new FamilyCount("ZONE", 1),
                  new FamilyCount("ROOM", 1),
                  new FamilyCount("ROOM_EXIT", 1),
                  new FamilyCount("GENERATION_RULE", 0),
                  new FamilyCount("WORLD_ENTITY_SPAWN_BINDING", 0)),
              List.of(new RegionGeneratorInput(id("15151515-1515-4515-8515-151515151515"), "", "")),
              List.of("id", "name", "scopeType", "scopeId", "value"),
              List.of(
                  "id",
                  "shardId",
                  "name",
                  "weather",
                  "generationSeed",
                  "generatorType",
                  "generatorParams",
                  "spacingMultiplier"),
              List.of("id", "regionId", "name"),
              List.of(
                  "id",
                  "zoneId",
                  "name",
                  "description",
                  "nameLocalizedVariantsJson",
                  "descriptionLocalizedVariantsJson"),
              List.of("id", "fromRoomId", "toRoomId", "direction", "cost"),
              List.of(
                  "id",
                  "roomId",
                  "entityTemplateType",
                  "entityReference.kind",
                  "entityReference.tenantId",
                  "entityReference.versionId",
                  "entityReference.templateId",
                  "spawnCount",
                  "respawnDelaySeconds"),
              List.of(),
              0,
              0,
              selected.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
                  .map(
                      unit ->
                          new AppliedEpoch(
                              unit.aggregateType(),
                              unit.aggregateId(),
                              unit.scopeType(),
                              unit.scopeId(),
                              unit.expectedEpoch(),
                              new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
                  .toList(),
              emptyInboundClosure());
      var ownerScope =
          new PublicOwnerScope(
              request.targetNamespace(),
              target.canonicalTenantId(),
              target.canonicalVersionId(),
              id("14141414-1414-4414-8414-141414141414"),
              acknowledgement.intakeRequestId(),
              id("13131313-1313-4313-8313-131313131313"),
              "sha256:" + "1".repeat(64),
              id("17171717-1717-4717-8717-171717171717"),
              "sha256:" + "2".repeat(64),
              "sha256:" + "3".repeat(64));
      return new PublicEvidence(
          WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA,
          WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION,
          "COMPLETE",
          ownerScope,
          new SelectedApplication(
              id("16161616-1616-4616-8616-161616161616"),
              selected.requestId(),
              selected.commitId().toString(),
              selected.digest(),
              "sha256:" + "6".repeat(64)),
          new PublicAccountOrder(
              account.operationId(),
              account.fenceId(),
              account.input().actorAccountId(),
              selection.intent().publishRequestId(),
              selection.digest(),
              selected.commitId().toString(),
              DraftAuthorizationFenceBinding.digest(account.canonicalBytes())),
          new Freeze(
              acknowledgement.publicationFence(),
              request.publicationRequestId(),
              request.requestDigest(),
              Long.toString(acknowledgement.versionStateEpoch()),
              PublicationDigestRequestBinding.full(
                      request.canonicalTenantId().toString(),
                      Long.toString(selection.target().gameDesignVersionRowId()),
                      request.publicationRequestId())
                  .derivedWorkflowIdentity()),
          new Checkpoint(acknowledgement.appliedCommitId(), acknowledgement.contentDigest(), 4),
          sourceModel,
          List.of(
              new ArtifactDecision(
                  "NAVMESH", "NOT_REQUIRED", "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
              new ArtifactDecision(
                  "PATH_GRAPH",
                  "NOT_REQUIRED",
                  "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
    }

    private static InboundSourceClosureDeclaration emptyInboundClosure() {
      return new InboundSourceClosureDeclaration(
          1,
          List.of(
              new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT", 0),
              new InboundSourceFamilyCount(
                  "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT", 0),
              new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION", 0),
              new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING", 0),
              new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK", 0),
              new InboundSourceFamilyCount(
                  "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE", 0),
              new InboundSourceFamilyCount(
                  "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING", 0)));
    }

    private static void frame(ByteArrayOutputStream out, String value) {
      DraftAuthorizationFenceBinding.frame(out, value);
    }

    private static void frame(ByteArrayOutputStream out, byte[] value) {
      DraftAuthorizationFenceBinding.frame(out, value);
    }

    private static UUID id(String value) {
      return UUID.fromString(value);
    }

    public record Fixture(
        SelectedOwnerIntakeAuthorizationBinding authorization,
        WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
        SelectedOwnerWorldInventoryReadEvidence worldEvidence,
        SelectedOwnerEmptySourceInputs inputs) {}
  }
}
