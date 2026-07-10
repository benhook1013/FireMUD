package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.gamedesign.dto.HelpTopicDto;
import net.firedevops.firemud.gamedesign.entity.GameAuthoredHelpTopic;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredHelpTopicRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories are internal Spring collaborators.")
public class GameAuthoredHelpTopicServiceImpl implements GameAuthoredHelpTopicService {
  private final GameAuthoredHelpTopicRepository repository;
  private final GameTemplateRepository gameTemplateRepository;

  public GameAuthoredHelpTopicServiceImpl(
      GameAuthoredHelpTopicRepository repository, GameTemplateRepository gameTemplateRepository) {
    this.repository = repository;
    this.gameTemplateRepository = gameTemplateRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<HelpTopicDto> resolvePublishedTopic(
      String tenantId, long gameTemplateId, String topic) {
    Scope scope = requireScope(tenantId, gameTemplateId);
    String normalizedTopic = normalizeKey(topic, "topic");
    return repository
        .findPublishedByCanonicalKey(scope.tenantId(), scope.gameTemplateId(), normalizedTopic)
        .or(
            () ->
                repository.findPublishedByAliasKey(
                    scope.tenantId(), scope.gameTemplateId(), normalizedTopic))
        .map(this::toDto);
  }

  @Override
  @Transactional
  public HelpTopicDto putTopic(String tenantId, long gameTemplateId, HelpTopicDto topic) {
    Scope scope = requireScope(tenantId, gameTemplateId);
    requireTemplate(scope);
    if (topic == null) {
      throw new IllegalArgumentException("helpTopic is required");
    }
    String canonicalTopicKey = normalizeKey(topic.canonicalTopicId(), "canonicalTopicId");
    List<String> aliases = normalizeAliases(topic.aliases(), canonicalTopicKey);
    GameAuthoredHelpTopic entity =
        repository
            .findByScopeAndCanonicalKey(scope.tenantId(), scope.gameTemplateId(), canonicalTopicKey)
            .orElseGet(GameAuthoredHelpTopic::new);
    requireUniqueKeys(scope, entity.getId(), canonicalTopicKey, aliases);
    entity.setTenantId(scope.tenantId());
    entity.setGameTemplateId(scope.gameTemplateId());
    entity.setCanonicalTopicKey(canonicalTopicKey);
    entity.setTitle(requireText(topic.title(), "title"));
    entity.setBody(requireText(topic.body(), "body"));
    entity.setPublished(topic.published());
    GameAuthoredHelpTopic saved = repository.save(entity);
    repository.replaceAliases(saved, aliases);
    saved.setAliases(aliases);
    return toDto(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<HelpTopicDto> listTopics(String tenantId, long gameTemplateId) {
    Scope scope = requireScope(tenantId, gameTemplateId);
    requireTemplate(scope);
    return repository.findAllByScope(scope.tenantId(), scope.gameTemplateId()).stream()
        .map(this::toDto)
        .toList();
  }

  @Override
  @Transactional
  public void deleteTopic(String tenantId, long gameTemplateId, String canonicalTopicId) {
    Scope scope = requireScope(tenantId, gameTemplateId);
    requireTemplate(scope);
    String canonicalTopicKey = normalizeKey(canonicalTopicId, "canonicalTopicId");
    GameAuthoredHelpTopic topic =
        repository
            .findByScopeAndCanonicalKey(scope.tenantId(), scope.gameTemplateId(), canonicalTopicKey)
            .orElseThrow(() -> new IllegalArgumentException("NOT_FOUND: help topic not found"));
    repository.delete(topic);
  }

  private Scope requireScope(String tenantId, long gameTemplateId) {
    String normalizedTenantId = requireText(tenantId, "tenantId");
    if (gameTemplateId <= 0) {
      throw new IllegalArgumentException("gameTemplateId must be positive");
    }
    return new Scope(normalizedTenantId, gameTemplateId);
  }

  private void requireTemplate(Scope scope) {
    if (gameTemplateRepository
        .findByTenantIdAndId(scope.tenantId(), scope.gameTemplateId())
        .isEmpty()) {
      throw new IllegalArgumentException("NOT_FOUND: game template not found");
    }
  }

  private List<String> normalizeAliases(List<String> aliases, String canonicalTopicKey) {
    if (aliases == null || aliases.isEmpty()) {
      return List.of();
    }
    Set<String> normalizedAliases = new LinkedHashSet<>();
    for (String alias : aliases) {
      String normalizedAlias = normalizeKey(alias, "alias");
      if (normalizedAlias.equals(canonicalTopicKey)) {
        throw new IllegalArgumentException("aliases must not repeat canonicalTopicId");
      }
      if (!normalizedAliases.add(normalizedAlias)) {
        throw new IllegalArgumentException("aliases must be unique after normalization");
      }
    }
    return new ArrayList<>(normalizedAliases);
  }

  private void requireUniqueKeys(
      Scope scope, Long topicId, String canonicalTopicKey, List<String> aliases) {
    requireNotClaimedByAnotherTopic(
        repository.findByScopeAndAliasKey(
            scope.tenantId(), scope.gameTemplateId(), canonicalTopicKey),
        topicId,
        "canonicalTopicId conflicts with an existing alias");
    for (String alias : aliases) {
      requireNotClaimedByAnotherTopic(
          repository.findByScopeAndCanonicalKey(scope.tenantId(), scope.gameTemplateId(), alias),
          topicId,
          "alias conflicts with an existing canonicalTopicId");
      requireNotClaimedByAnotherTopic(
          repository.findByScopeAndAliasKey(scope.tenantId(), scope.gameTemplateId(), alias),
          topicId,
          "alias conflicts with an existing alias");
    }
  }

  private void requireNotClaimedByAnotherTopic(
      Optional<GameAuthoredHelpTopic> existing, Long topicId, String message) {
    if (existing.isPresent()
        && !java.util.Objects.equals(existing.orElseThrow().getId(), topicId)) {
      throw new IllegalArgumentException(message);
    }
  }

  private String normalizeKey(String value, String field) {
    return requireText(value, field).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  private HelpTopicDto toDto(GameAuthoredHelpTopic topic) {
    return new HelpTopicDto(
        topic.getCanonicalTopicKey(),
        topic.getTitle(),
        topic.getBody(),
        List.copyOf(topic.getAliases()),
        topic.isPublished());
  }

  private record Scope(String tenantId, long gameTemplateId) {}
}
