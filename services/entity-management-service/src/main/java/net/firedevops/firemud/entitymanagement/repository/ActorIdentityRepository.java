package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CHARACTERS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentity;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * Exact, uncached canonical actor lookup. It intentionally has no write or resolution operation.
 */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ActorIdentityRepository {
  private final DSLContext dsl;

  public ActorIdentityRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public List<ActorIdentity> findOwnerResolvedRoster(
      String tenantUuid,
      String accountUuid,
      String playableStateNamespaceId,
      PlayableStateScope playableStateScope) {
    UUID tenant = ActorIdentity.parseRequiredUuid(tenantUuid, "tenantUuid");
    UUID account = ActorIdentity.parseRequiredUuid(accountUuid, "accountUuid");
    UUID namespace =
        ActorIdentity.parseRequiredUuid(playableStateNamespaceId, "playableStateNamespaceId");
    PlayableStateScope scope = ActorIdentity.requireScope(playableStateScope);
    if (scope != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        && scope != PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED) {
      throw new IllegalArgumentException("playableStateScope is not a supported resolved scope");
    }
    return dsl.select(
            CHARACTERS.CHARACTER_UUID,
            CHARACTERS.ACCOUNT_UUID,
            CHARACTERS.TENANT_UUID,
            CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID,
            CHARACTERS.PLAYABLE_STATE_SCOPE,
            CHARACTERS.ACTOR_IDENTITY_STATUS,
            CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON)
        .from(CHARACTERS)
        .where(
            CHARACTERS
                .ACTOR_IDENTITY_STATUS
                .eq(ActorIdentityStatus.OWNER_RESOLVED.name())
                .and(CHARACTERS.TENANT_UUID.eq(tenant))
                .and(CHARACTERS.ACCOUNT_UUID.eq(account))
                .and(CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID.eq(namespace))
                .and(CHARACTERS.PLAYABLE_STATE_SCOPE.eq(scope.name())))
        .orderBy(CHARACTERS.CHARACTER_UUID.asc())
        .fetch(this::toIdentity);
  }

  public Optional<ActorIdentity> findOwnerResolvedActor(
      String tenantUuid,
      String accountUuid,
      String playableStateNamespaceId,
      PlayableStateScope playableStateScope,
      String characterUuid) {
    UUID tenant = ActorIdentity.parseRequiredUuid(tenantUuid, "tenantUuid");
    UUID account = ActorIdentity.parseRequiredUuid(accountUuid, "accountUuid");
    UUID namespace =
        ActorIdentity.parseRequiredUuid(playableStateNamespaceId, "playableStateNamespaceId");
    PlayableStateScope scope = ActorIdentity.requireScope(playableStateScope);
    UUID requestedCharacter = ActorIdentity.parseRequiredUuid(characterUuid, "characterUuid");
    return Optional.ofNullable(
        dsl.select(
                CHARACTERS.CHARACTER_UUID,
                CHARACTERS.ACCOUNT_UUID,
                CHARACTERS.TENANT_UUID,
                CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID,
                CHARACTERS.PLAYABLE_STATE_SCOPE,
                CHARACTERS.ACTOR_IDENTITY_STATUS,
                CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON)
            .from(CHARACTERS)
            .where(
                CHARACTERS
                    .ACTOR_IDENTITY_STATUS
                    .eq(ActorIdentityStatus.OWNER_RESOLVED.name())
                    .and(CHARACTERS.TENANT_UUID.eq(tenant))
                    .and(CHARACTERS.ACCOUNT_UUID.eq(account))
                    .and(CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID.eq(namespace))
                    .and(CHARACTERS.PLAYABLE_STATE_SCOPE.eq(scope.name()))
                    .and(CHARACTERS.CHARACTER_UUID.eq(requestedCharacter)))
            .fetchOne(this::toIdentity));
  }

  private ActorIdentity toIdentity(Record record) {
    return new ActorIdentity(
        record.get(CHARACTERS.CHARACTER_UUID),
        record.get(CHARACTERS.ACCOUNT_UUID),
        record.get(CHARACTERS.TENANT_UUID),
        record.get(CHARACTERS.PLAYABLE_STATE_NAMESPACE_ID),
        PlayableStateScope.valueOf(record.get(CHARACTERS.PLAYABLE_STATE_SCOPE)),
        ActorIdentityStatus.valueOf(record.get(CHARACTERS.ACTOR_IDENTITY_STATUS)),
        record.get(CHARACTERS.ACTOR_IDENTITY_QUARANTINE_REASON));
  }
}
