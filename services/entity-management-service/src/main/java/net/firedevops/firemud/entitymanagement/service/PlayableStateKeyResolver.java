package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlayableStateKeyResolver {
  private static final String SHARED_LIVE_KEY = "shared-live";
  private static final String INSTANCE_PREFIX = "instance:";

  public String resolve(String gameInstanceId, PlayableStateScope scope) {
    if (!StringUtils.hasText(gameInstanceId)) {
      throw new IllegalArgumentException("gameInstanceId must not be blank");
    }
    return switch (scope) {
      case PLAYABLE_STATE_SCOPE_SHARED -> SHARED_LIVE_KEY;
      case PLAYABLE_STATE_SCOPE_ISOLATED -> INSTANCE_PREFIX + gameInstanceId.trim();
      case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("playableStateScope must be specified");
    };
  }

  public PlayableStateScope resolveScope(String playableStateKey) {
    if (!StringUtils.hasText(playableStateKey)) {
      throw new IllegalArgumentException("playableStateKey must not be blank");
    }
    String normalized = playableStateKey.trim();
    if (SHARED_LIVE_KEY.equals(normalized)) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
    }
    if (normalized.startsWith(INSTANCE_PREFIX)) {
      return PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
    }
    throw new IllegalArgumentException("Unsupported playableStateKey: " + playableStateKey);
  }
}
