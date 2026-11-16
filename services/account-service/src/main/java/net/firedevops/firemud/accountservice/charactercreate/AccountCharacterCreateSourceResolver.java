package net.firedevops.firemud.accountservice.charactercreate;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse;
import tools.jackson.databind.json.JsonMapper;

/** Unregistered source composition; owner protection and Entity delegation remain separate. */
public final class AccountCharacterCreateSourceResolver
    implements AccountCharacterCreateOwnerClients {
  private final String namespace;
  private final AccountCharacterCreateScopeService scopes;
  private final AccountCharacterCreatePublicSourceClient sources;

  public AccountCharacterCreateSourceResolver(
      String namespace,
      AccountCharacterCreateScopeService scopes,
      AccountCharacterCreatePublicSourceClient sources) {
    this.namespace = AccountCharacterCreateDelegationBindings.namespace(namespace);
    this.scopes = Objects.requireNonNull(scopes);
    this.sources = Objects.requireNonNull(sources);
  }

  @Override
  public ResolvedSource resolvePublicProduction(
      String requestedNamespace, AccountCharacterCreatePrincipal principal, String opaqueScope) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    if (!namespace.equals(requestedNamespace) || principal == null) throw invalid();
    UUID account = Objects.requireNonNull(principal.authenticateCurrentAccount());
    var scope = Objects.requireNonNull(scopes.verify(principal, opaqueScope));
    if (!account.equals(scope.accountId())
        || !opaqueScope.equals(scope.connectScopeId())
        || !"SHARED".equals(scope.playableStateScope())) throw invalid();
    var request =
        ReadCharacterCreatePublicSourceRequest.newBuilder()
            .setSchemaVersion(1)
            .setReadRequestId(UUID.randomUUID().toString())
            .setTargetNamespace(namespace)
            .setCanonicalTenantId(scope.tenantId().toString())
            .setRealmId(scope.realmId().toString())
            .setWorldSlug(scope.worldSlug())
            .setRealmSlug(scope.realmSlug())
            .setPlayableStateNamespaceId(scope.playableStateNamespaceId().toString())
            .setPlayableStateScope(scope.playableStateScope())
            .setCanonicalGameInstanceId(scope.gameInstanceId().toString())
            .setCatalogRevision(scope.catalogRevision())
            .setPointerVersion(scope.pointerVersion())
            .setEvaluatedAt(scope.evaluatedAt())
            .setConnectScopeExpiresAt(scope.connectScopeExpiresAt())
            .build();
    var source = Objects.requireNonNull(sources.read(request));
    var response = Objects.requireNonNull(source.response());
    if (!request.equals(response.getRequest())
        || !scope.tenantSlug().equals(response.getCatalog().getTenantSlug())) throw invalid();
    byte[] action = action(response, account);
    return new ResolvedSource(action, response.toByteArray());
  }

  @Override
  public void requireExactOriginalSource(AccountCharacterCreateIntent intent) {
    AccountCharacterCreateDelegationBindings.outsideSql();
    if (!namespace.equals(intent.targetNamespace())) throw invalid();
    byte[] retained = intent.originalSourceReadback();
    ReadCharacterCreatePublicSourceResponse original;
    try {
      original = ReadCharacterCreatePublicSourceResponse.parseFrom(retained);
    } catch (InvalidProtocolBufferException malformed) {
      throw invalid();
    }
    if (!Arrays.equals(retained, original.toByteArray())
        || !Arrays.equals(intent.actionBytes(), action(original, intent.accountId())))
      throw invalid();
    var current = Objects.requireNonNull(sources.read(original.getRequest())).response();
    if (!Arrays.equals(retained, current.toByteArray())) throw invalid();
    // Decode the actual reread as well; no new scope, target, clock or expiry is introduced.
    if (!Arrays.equals(intent.actionBytes(), action(current, intent.accountId()))) throw invalid();
  }

  @Override
  public void delegateProtected(
      AccountCharacterCreateIntent intent, AccountCharacterCreateRepository.Protection protection) {
    throw Status.UNAVAILABLE
        .withDescription("CHARACTER_CREATE source resolver supplies no owner delegation")
        .asRuntimeException();
  }

  /** Rebuilds the exact action only from the complete closed owner source and original selector. */
  private byte[] action(ReadCharacterCreatePublicSourceResponse response, UUID account) {
    var request = response.getRequest();
    var catalog = response.getCatalog();
    var pointer = response.getPointer();
    if (!response.getUnknownFields().asMap().isEmpty()
        || !request.getUnknownFields().asMap().isEmpty()
        || !catalog.getUnknownFields().asMap().isEmpty()
        || !pointer.getUnknownFields().asMap().isEmpty()
        || response.getSchemaVersion() != 1
        || request.getSchemaVersion() != 1
        || response.getSerializedSize()
            > AccountCharacterCreatePublicSourceClient.MAX_SOURCE_WIRE_BYTES
        || !namespace.equals(request.getTargetNamespace())
        || !"SHARED".equals(request.getPlayableStateScope())
        || !response.hasAuthoredSource()
        || response.getWorldLifecycleEvidence().isEmpty()
        || !request.getCanonicalTenantId().equals(catalog.getTenantId())
        || !request.getRealmId().equals(catalog.getRealmId())
        || !request.getWorldSlug().equals(catalog.getWorldSlug())
        || !request.getRealmSlug().equals(catalog.getRealmSlug())
        || !request.getPlayableStateNamespaceId().equals(catalog.getPlayableStateNamespaceId())
        || request.getCatalogRevision() != catalog.getCatalogRevision()
        || !request.getCanonicalTenantId().equals(pointer.getTenantId())
        || !request.getRealmId().equals(pointer.getRealmId())
        || !request.getWorldSlug().equals(pointer.getWorldSlug())
        || !request.getRealmSlug().equals(pointer.getRealmSlug())
        || !request.getCanonicalGameInstanceId().equals(pointer.getAdmissibleGameInstanceId())
        || request.getCatalogRevision() != pointer.getCatalogRevision()
        || request.getPointerVersion() != pointer.getPointerVersion()
        || response.getActiveLifecycleEpoch() <= 0
        || response.getOriginalOwnerFence() <= 0) throw invalid();
    AccountCharacterCreateDelegationBindings.id(request.getReadRequestId());
    AccountCharacterCreateDelegationBindings.id(catalog.getCreationRequestId());
    AccountCharacterCreateDelegationBindings.instant(request.getEvaluatedAt());
    AccountCharacterCreateDelegationBindings.instant(request.getConnectScopeExpiresAt());
    AccountCharacterCreateDelegationBindings.instant(pointer.getUpdatedAt());
    if (!Instant.parse(request.getEvaluatedAt())
        .isBefore(Instant.parse(request.getConnectScopeExpiresAt()))) throw invalid();
    if (!catalog.getReceiptDigest().matches("sha256:[0-9a-f]{64}")
        || pointer.getInitialAdmissionRequestId().isBlank()
        || !pointer.getInitialAdmissionOwnerProofDigest().matches("sha256:[0-9a-f]{64}")
        || !pointer.getAdmissionPointerSnapshotDigest().matches("[0-9a-f]{64}")) throw invalid();
    var authored =
        AuthoredInitialActorCoreReadGrpcCodec.fromResponse(
            StartSessionTemplateAssociationReadGrpcCodec.fromRequest(
                response.getAuthoredSource().getRequest()),
            response.getAuthoredSource());
    var world =
        WorldCanonicalInstanceLifecycleEvidence.fromStored(
            response.getWorldLifecycleEvidence().toByteArray());
    var association = authored.associationRead().association();
    var lifecycle = world.request();
    if (!namespace.equals(authored.request().targetNamespace())
        || !response
            .getOriginalOwnerAttemptId()
            .equals(authored.request().ownerAttemptId().toString())
        || response.getOriginalOwnerFence() != authored.request().ownerFence()
        || !response
            .getOriginalControlPlaneRequestId()
            .equals(authored.request().decodedTuple().controlPlaneRequestId())
        || !authored.descriptor().equals(world.launchBinding().descriptor())
        || !association
            .publishWorkflowId()
            .equals(world.launchBinding().releaseAttestation().publishWorkflowId())
        || !association
            .selectedCommitId()
            .toString()
            .equals(world.launchBinding().releaseAttestation().commitId())
        || !request.getCanonicalTenantId().equals(association.canonicalTenantId().toString())
        || !request.getWorldSlug().equals(association.worldSlug())
        || !response.getCanonicalVersionId().equals(association.canonicalVersionId().toString())
        || !namespace.equals(lifecycle.targetNamespace())
        || !request.getCanonicalTenantId().equals(lifecycle.canonicalTenantId().toString())
        || !request.getWorldSlug().equals(lifecycle.worldSlug())
        || !request
            .getCanonicalGameInstanceId()
            .equals(lifecycle.canonicalGameInstanceId().toString())
        || !request
            .getPlayableStateNamespaceId()
            .equals(lifecycle.playableStateNamespaceId().toString())
        || !response.getCanonicalVersionId().equals(lifecycle.canonicalVersionId().toString())
        || !response.getOriginalControlPlaneRequestId().equals(lifecycle.controlPlaneRequestId())
        || !"SHARED".equals(lifecycle.playableStateScope())
        || !lifecycle.publicProduction()
        || !"ACTIVE".equals(world.lifecycleStatus())
        || response.getActiveLifecycleEpoch() != world.lifecycleEpoch()) throw invalid();
    var json = JsonMapper.builder().build();
    var selected =
        authored.projections().stream()
            .filter(
                projection ->
                    RealmEntryPolicy.parseCanonical(projection.policy().canonicalJson(), json)
                        .realmSlug()
                        .equals(request.getRealmSlug()))
            .toList();
    if (selected.size() != 1) throw invalid();
    var projection = selected.getFirst();
    var policy = projection.policy();
    var raw =
        new CharacterCreateOperatorAction(
            new CharacterCreateOperatorAction.Scope(
                namespace,
                request.getCanonicalTenantId(),
                request.getPlayableStateNamespaceId(),
                CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED),
            new CharacterCreateOperatorAction.Target(
                account.toString(),
                request.getRealmId(),
                request.getWorldSlug(),
                request.getRealmSlug(),
                request.getCanonicalGameInstanceId(),
                response.getCanonicalVersionId()),
            new CharacterCreateOperatorAction.ExpectedVersion(
                Long.toString(request.getCatalogRevision()),
                Long.toString(request.getPointerVersion()),
                Long.toString(response.getActiveLifecycleEpoch()),
                Instant.parse(request.getEvaluatedAt()),
                Instant.parse(request.getConnectScopeExpiresAt())),
            new CharacterCreateOperatorAction.Mutation(
                "CHARACTER_CREATE",
                "PLAYER_CREATED",
                new CharacterCreateOperatorAction.Catalog(
                    catalog.getTenantId(),
                    catalog.getTenantSlug(),
                    catalog.getWorldSlug(),
                    catalog.getWorldDisplayName(),
                    catalog.getRealmId(),
                    catalog.getRealmSlug(),
                    catalog.getRealmDisplayName(),
                    catalog.getVisible(),
                    catalog.getPublicProduction(),
                    catalog.getStateScope(),
                    catalog.getPlayableStateNamespaceId(),
                    catalog.getCharacterCreationPolicy(),
                    Long.toString(catalog.getCatalogRevision())),
                new CharacterCreateOperatorAction.Pointer(
                    pointer.getTenantId(),
                    pointer.getRealmId(),
                    pointer.getWorldSlug(),
                    pointer.getRealmSlug(),
                    pointer.getAdmissionState(),
                    pointer.getAdmissibleGameInstanceId(),
                    Long.toString(pointer.getPointerVersion()),
                    Long.toString(pointer.getCatalogRevision()),
                    Instant.parse(pointer.getUpdatedAt())),
                new CharacterCreateOperatorAction.AuthoredSource(
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
                    rule(projection.disposition()),
                    rule(projection.safeFeedback())),
                "{}"),
            CharacterCreateOperatorAction.AuditReason.ABSENT);
    return CharacterCreateOperatorActionCodec.encode(raw);
  }

  private static CharacterCreateOperatorAction.Rule rule(
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

  private static RuntimeException invalid() {
    return AccountCharacterCreateDelegationBindings.invalid();
  }
}
