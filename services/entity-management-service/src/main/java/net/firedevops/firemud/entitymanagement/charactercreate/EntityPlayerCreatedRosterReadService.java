package net.firedevops.firemud.entitymanagement.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;

/**
 * Unregistered Entity composition for persisted PLAYER_CREATED roster discovery. It allocates no
 * actor and establishes no selection, admission, activation, retirement, or PKI authority.
 */
public final class EntityPlayerCreatedRosterReadService {
  private final EntityCharacterCreateRepository repository;
  private final EntityPlayerCreatedRosterSourceClient sourceClient;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected owner client and repository are internal service collaborators.")
  public EntityPlayerCreatedRosterReadService(
      EntityCharacterCreateRepository repository,
      EntityPlayerCreatedRosterSourceClient sourceClient) {
    this.repository = Objects.requireNonNull(repository);
    this.sourceClient = Objects.requireNonNull(sourceClient);
  }

  /** Reads the authenticated source before entering the short Entity owner transaction. */
  public List<CanonicalGameplayRosterActor> read(
      AuthenticatedAccountSelector account, ReadPlayerCreatedRosterSourceRequest request) {
    EntityCharacterCreateRepository.outsideTransaction();
    var source = sourceClient.read(Objects.requireNonNull(request));
    return read(account, source);
  }

  /** The source type is constructible only by the mTLS client that actually observed its owner. */
  public List<CanonicalGameplayRosterActor> read(
      AuthenticatedAccountSelector account,
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source) {
    EntityCharacterCreateRepository.outsideTransaction();
    return repository.readPlayerCreatedRoster(
        Objects.requireNonNull(account), PublishedIdentity.from(Objects.requireNonNull(source)));
  }

  /** Reads the current authenticated source before the short selected-terminal owner read. */
  public EntityCharacterCreateTerminal readSelectedTerminal(
      AuthenticatedAccountSelector account,
      UUID characterId,
      ReadPlayerCreatedRosterSourceRequest request) {
    EntityCharacterCreateRepository.outsideTransaction();
    Objects.requireNonNull(account, "account");
    EntityCharacterCreateRepository.requireCanonicalCharacterId(characterId);
    var source = sourceClient.read(Objects.requireNonNull(request, "request"));
    return readSelectedTerminal(account, characterId, source);
  }

  /** The source type is constructible only by the mTLS client that actually observed its owner. */
  EntityCharacterCreateTerminal readSelectedTerminal(
      AuthenticatedAccountSelector account,
      UUID characterId,
      EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source) {
    return readSelectedTerminal(
        account, characterId, PublishedIdentity.from(Objects.requireNonNull(source, "source")));
  }

  EntityCharacterCreateTerminal readSelectedTerminal(
      AuthenticatedAccountSelector account, UUID characterId, PublishedIdentity published) {
    EntityCharacterCreateRepository.outsideTransaction();
    return repository.readSelectedPlayerCreatedTerminal(
        Objects.requireNonNull(account, "account"),
        Objects.requireNonNull(published, "published"),
        characterId);
  }

  /** Account authentication is supplied by the owning caller; this value is only its selector. */
  public record AuthenticatedAccountSelector(UUID accountId) {
    public AuthenticatedAccountSelector {
      Objects.requireNonNull(accountId, "accountId");
      if (accountId.equals(new UUID(0L, 0L)))
        throw new IllegalArgumentException("accountId must be non-nil");
    }
  }

  record PublishedIdentity(
      String targetNamespace,
      UUID tenantId,
      UUID realmId,
      String worldSlug,
      String realmSlug,
      UUID playableStateNamespaceId,
      UUID canonicalVersionId,
      AuthoredInitialActorCoreProjection projection) {
    static PublishedIdentity from(
        EntityPlayerCreatedRosterSourceClient.AuthenticatedSource source) {
      var target = source.target();
      var request = source.response().getRequest();
      if (target.entryPolicy() != CanonicalGameplayRosterEntryPolicy.PLAYER_CREATED
          || target.playableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
          || source.authored().projections().size() != 1
          || !request.getCanonicalTenantId().equals(target.tenantUuid().toString())
          || !request.getRealmId().equals(target.realmUuid().toString())
          || !request.getWorldSlug().equals(target.worldSlug())
          || !request.getRealmSlug().equals(target.realmSlug())
          || !request
              .getPlayableStateNamespaceId()
              .equals(target.playableStateNamespaceId().toString())
          || !request
              .getExpectedCanonicalVersionId()
              .equals(target.canonicalVersionUuid().toString())) throw invalid();
      return new PublishedIdentity(
          request.getTargetNamespace(),
          target.tenantUuid(),
          target.realmUuid(),
          target.worldSlug(),
          target.realmSlug(),
          target.playableStateNamespaceId(),
          target.canonicalVersionUuid(),
          source.authored().projections().getFirst());
    }

    boolean matches(CharacterCreateOperatorAction action) {
      var scope = action.scope();
      var target = action.target();
      var catalog = action.mutation().catalog();
      var authored = action.mutation().authoredSource();
      return targetNamespace.equals(scope.targetNamespace())
          && tenantId.toString().equals(scope.tenantId())
          && playableStateNamespaceId.toString().equals(scope.playableStateNamespaceId())
          && scope.playableStateScope()
              == CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
          && realmId.toString().equals(target.realmId())
          && worldSlug.equals(target.worldSlug())
          && realmSlug.equals(target.realmSlug())
          && canonicalVersionId.toString().equals(target.versionId())
          && "CHARACTER_CREATE".equals(action.mutation().purpose())
          && "PLAYER_CREATED".equals(action.mutation().entryPolicy())
          && catalog.visible()
          && catalog.publicProduction()
          && "SHARED".equals(catalog.stateScope())
          && "PLAYER_CREATED".equals(catalog.characterCreationPolicy())
          && tenantId.toString().equals(catalog.tenantId())
          && realmId.toString().equals(catalog.realmId())
          && worldSlug.equals(catalog.worldSlug())
          && realmSlug.equals(catalog.realmSlug())
          && playableStateNamespaceId.toString().equals(catalog.playableStateNamespaceId())
          && authored.schema().equals(AuthoredInitialActorCoreProjection.SCHEMA)
          && authored.bindingId().equals(projection.bindingId().toString())
          && authored.bindingDigest().equals(projection.bindingDigest())
          && authored.canonicalTenantId().equals(projection.canonicalTenantId().toString())
          && authored.canonicalVersionId().equals(projection.canonicalVersionId().toString())
          && authored
              .worldSourceOperationId()
              .equals(projection.worldSourceOperationId().toString())
          && authored.worldSourceEvidenceDigest().equals(projection.worldSourceEvidenceDigest())
          && authored.worldSlug().equals(projection.worldSlug())
          && authored.publicationWorkflowId().equals(projection.publicationWorkflowId())
          && authored.selectedCommitId().equals(projection.selectedCommitId().toString())
          && authored.selectedCommitDigest().equals(projection.selectedCommitDigest())
          && authored.policy().commitId().equals(projection.policy().commitId().toString())
          && authored.policy().revisionId().equals(projection.policy().revisionId().toString())
          && authored.policy().logicalRevisionId().equals(projection.policy().logicalRevisionId())
          && authored.policy().canonicalPolicyJson().equals(projection.policy().canonicalJson())
          && authored.policy().contentDigest().equals(projection.policy().contentDigest())
          && authored.core().canonicalJson().equals(projection.core().canonicalJson())
          && authored.descriptor().canonicalJson().equals(projection.descriptor().canonicalJson())
          && authored.descriptor().digest().equals(projection.descriptor().digest())
          && same(authored.disposition(), projection.disposition())
          && same(authored.safeFeedback(), projection.safeFeedback());
    }

    private static boolean same(
        CharacterCreateOperatorAction.Rule expected,
        AuthoredInitialActorCoreProjection.RuleDefinition actual) {
      return expected.key().equals(actual.key())
          && expected.definitionJson().equals(actual.definitionJson())
          && expected.definitionDigest().equals(actual.definitionDigest())
          && expected.sourceCommitId().equals(actual.sourceCommitId().toString())
          && expected.sourceCommitDigest().equals(actual.sourceCommitDigest())
          && expected.revisionId().equals(actual.revisionId().toString())
          && expected.revisionOrder().equals(actual.revisionOrder());
    }

    private static RuntimeException invalid() {
      return EntityCharacterCreateBindings.invalid();
    }
  }
}
