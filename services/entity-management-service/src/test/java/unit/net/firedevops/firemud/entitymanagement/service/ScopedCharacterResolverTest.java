package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentity;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;

class ScopedCharacterResolverTest {
  @Test
  void rejectsLegacyScopeEvenWhenRepositoryCanReturnOwnerResolvedCandidate() {
    CharacterRepository repository = mock(CharacterRepository.class);
    Character candidate = new Character();
    candidate.setId(17L);
    candidate.setTenantId(23L);
    candidate.setActorIdentity(
        new ActorIdentity(
            UUID.fromString("40000000-0000-4000-8000-000000000001"),
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            ActorIdentityStatus.OWNER_RESOLVED,
            null));
    when(repository.findByIdAndTenantIdAndPlayableStateKey(17L, 23L, "shared-live"))
        .thenReturn(Optional.of(candidate));

    ScopedCharacterResolver resolver =
        new ScopedCharacterResolver(repository, new PlayableStateKeyResolver());
    assertThatThrownBy(
            () ->
                resolver.requireScopedCharacter(
                    23L, 17L, "GI-1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OWNER_RESOLVED_ACTOR_IDENTITY_REQUIRED");
    verifyNoInteractions(repository);
  }
}
