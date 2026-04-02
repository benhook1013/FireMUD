package net.firedevops.firemud.gamesession.presentation;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Applies narrow per-session prompt throttling without globally batching normal output traffic. */
@Component
public class PromptBurstCoordinator {
  private final PresentationProperties presentationProperties;
  private final Clock clock;
  private final ConcurrentMap<String, Long> lastPromptEmissionBySession = new ConcurrentHashMap<>();

  @Autowired
  public PromptBurstCoordinator(PresentationProperties presentationProperties) {
    this(presentationProperties, Clock.systemUTC());
  }

  PromptBurstCoordinator(PresentationProperties presentationProperties, Clock clock) {
    this.presentationProperties = presentationProperties;
    this.clock = clock;
  }

  public List<PlayerOutput> applyPromptWindow(
      String sessionId, List<PlayerOutput> outputs, boolean forcePromptEmission) {
    if (!StringUtils.hasText(sessionId) || forcePromptEmission || !containsPrompt(outputs)) {
      return outputs;
    }
    long coalesceWindowMs = presentationProperties.prompt().coalesceWindowMs();
    if (coalesceWindowMs <= 0) {
      return outputs;
    }
    Long lastEmission = lastPromptEmissionBySession.get(sessionId);
    long now = clock.millis();
    if (lastEmission == null || now - lastEmission >= coalesceWindowMs) {
      return outputs;
    }
    return outputs.stream().filter(output -> output.kind() != PlayerOutputKind.PROMPT).toList();
  }

  public void recordPromptEmission(String sessionId, List<PlayerOutput> outputs) {
    if (!StringUtils.hasText(sessionId) || !containsPrompt(outputs)) {
      return;
    }
    lastPromptEmissionBySession.put(sessionId, clock.millis());
  }

  public void recordPromptEmission(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      return;
    }
    lastPromptEmissionBySession.put(sessionId, clock.millis());
  }

  public void evict(String sessionId) {
    if (!StringUtils.hasText(sessionId)) {
      return;
    }
    lastPromptEmissionBySession.remove(sessionId);
  }

  private boolean containsPrompt(List<PlayerOutput> outputs) {
    return outputs != null
        && outputs.stream().anyMatch(output -> output.kind() == PlayerOutputKind.PROMPT);
  }
}
