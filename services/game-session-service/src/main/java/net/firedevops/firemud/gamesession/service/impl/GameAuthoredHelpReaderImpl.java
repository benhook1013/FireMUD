package net.firedevops.firemud.gamesession.service.impl;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.GameAuthoredHelpReader;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Bridges an admitted runtime instance to its template-scoped authored help corpus. */
@Service
@RequiredArgsConstructor
public class GameAuthoredHelpReaderImpl implements GameAuthoredHelpReader {
  private final GameInstanceRepository gameInstanceRepository;
  private final GameDesignClient gameDesignClient;

  @Override
  public Optional<ResolvedTopic> resolve(SessionContext context, String topic) {
    if (context == null
        || !context.hasGameplayRegionBinding()
        || !StringUtils.hasText(topic)
        || context.tenantId() <= 0L
        || context.gameInstanceId() <= 0L) {
      return Optional.empty();
    }

    return gameInstanceRepository
        .findById(context.gameInstanceId())
        .filter(instance -> matchesAdmittedRuntime(context, instance))
        .map(GameInstance::getGameTemplateId)
        .filter(templateId -> templateId != null && templateId > 0L)
        .flatMap(
            templateId ->
                gameDesignClient.resolveAuthoredHelpTopic(context.tenantId(), templateId, topic));
  }

  private boolean matchesAdmittedRuntime(SessionContext context, GameInstance instance) {
    return instance.getTenantId() != null && instance.getTenantId() == context.tenantId();
  }
}
