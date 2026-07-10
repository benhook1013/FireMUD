package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.HelpTopicDto;
import net.firedevops.firemud.gamedesign.entity.GameAuthoredHelpTopic;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredHelpTopicRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class GameAuthoredHelpTopicServiceImplTest {
  private final GameAuthoredHelpTopicRepository repository =
      Mockito.mock(GameAuthoredHelpTopicRepository.class);
  private final GameTemplateRepository gameTemplateRepository =
      Mockito.mock(GameTemplateRepository.class);
  private final GameAuthoredHelpTopicServiceImpl service =
      new GameAuthoredHelpTopicServiceImpl(repository, gameTemplateRepository);

  @Test
  void resolvesPublishedCanonicalBeforeAlias() {
    GameAuthoredHelpTopic canonical =
        topic("movement", "Canonical movement", List.of("walk"), true);
    when(repository.findPublishedByCanonicalKey("42", 7L, "movement"))
        .thenReturn(Optional.of(canonical));

    Optional<HelpTopicDto> resolved = service.resolvePublishedTopic("42", 7L, " MOVEMENT ");

    assertThat(resolved)
        .hasValueSatisfying(topic -> assertThat(topic.title()).isEqualTo("Canonical movement"));
    verify(repository, never()).findPublishedByAliasKey("42", 7L, "movement");
  }

  @Test
  void resolvesPublishedAliasWithinExactTenantAndTemplateScope() {
    GameAuthoredHelpTopic alias = topic("travel", "Travel", List.of("move"), true);
    when(repository.findPublishedByCanonicalKey("42", 7L, "move")).thenReturn(Optional.empty());
    when(repository.findPublishedByAliasKey("42", 7L, "move")).thenReturn(Optional.of(alias));

    Optional<HelpTopicDto> resolved = service.resolvePublishedTopic("42", 7L, "Move");

    assertThat(resolved)
        .hasValueSatisfying(topic -> assertThat(topic.canonicalTopicId()).isEqualTo("travel"));
    verify(repository).findPublishedByCanonicalKey("42", 7L, "move");
    verify(repository).findPublishedByAliasKey("42", 7L, "move");
  }

  @Test
  void rejectsInvalidScopeBeforeReadingAnyTopic() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.resolvePublishedTopic("42", 0L, "movement"))
        .withMessage("gameTemplateId must be positive");

    verify(repository, never())
        .findPublishedByCanonicalKey(Mockito.anyString(), Mockito.anyLong(), Mockito.anyString());
  }

  @Test
  void normalizesPersistedTopicAndRejectsDuplicateAliases() {
    when(gameTemplateRepository.findByTenantIdAndId("42", 7L))
        .thenReturn(Optional.of(Mockito.mock()));
    GameAuthoredHelpTopic saved = topic("movement", "Movement", List.of(), true);
    saved.setId(11L);
    when(repository.findByScopeAndCanonicalKey("42", 7L, "movement")).thenReturn(Optional.empty());
    when(repository.save(Mockito.any())).thenReturn(saved);

    HelpTopicDto result =
        service.putTopic(
            "42",
            7L,
            new HelpTopicDto(" Movement ", "Movement", "Walk north.", List.of("walk"), true));

    ArgumentCaptor<GameAuthoredHelpTopic> savedCaptor =
        ArgumentCaptor.forClass(GameAuthoredHelpTopic.class);
    verify(repository).save(savedCaptor.capture());
    verify(repository).replaceAliases(saved, List.of("walk"));
    assertThat(savedCaptor.getValue().getCanonicalTopicKey()).isEqualTo("movement");
    assertThat(result.canonicalTopicId()).isEqualTo("movement");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.putTopic(
                    "42",
                    7L,
                    new HelpTopicDto("look", "Look", "Inspect.", List.of("SEE", "see"), false)))
        .withMessage("aliases must be unique after normalization");
  }

  @Test
  void rejectsAliasThatConflictsWithAnotherCanonicalTopic() {
    when(gameTemplateRepository.findByTenantIdAndId("42", 7L))
        .thenReturn(Optional.of(Mockito.mock()));
    GameAuthoredHelpTopic existing = topic("movement", "Movement", List.of(), true);
    existing.setId(8L);
    when(repository.findByScopeAndCanonicalKey("42", 7L, "travel")).thenReturn(Optional.empty());
    when(repository.findByScopeAndCanonicalKey("42", 7L, "move")).thenReturn(Optional.of(existing));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                service.putTopic(
                    "42",
                    7L,
                    new HelpTopicDto("travel", "Travel", "Walk onward.", List.of("move"), true)))
        .withMessage("alias conflicts with an existing canonicalTopicId");
  }

  private static GameAuthoredHelpTopic topic(
      String canonicalTopicKey, String title, List<String> aliases, boolean published) {
    GameAuthoredHelpTopic topic = new GameAuthoredHelpTopic();
    topic.setCanonicalTopicKey(canonicalTopicKey);
    topic.setTitle(title);
    topic.setBody(title + " body");
    topic.setAliases(aliases);
    topic.setPublished(published);
    return topic;
  }
}
