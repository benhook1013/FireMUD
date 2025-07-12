package net.firedevops.firemud.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.service.ScriptVersionService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Default in-memory implementation which simply logs reload requests. Real implementation would
 * reload affected scripts and update registries.
 */
@Service
@RequiredArgsConstructor
public class ScriptVersionServiceImpl implements ScriptVersionService {
  private static final Logger logger = LoggingUtil.getLogger(ScriptVersionServiceImpl.class);

  @Override
  public void notifyUpdate(Long gameId, String scriptPatchVersion, List<String> affectedScripts) {
    logger.info(
        "Applying script patch {} for game {} affecting {} scripts",
        scriptPatchVersion,
        gameId,
        affectedScripts.size());
    // TODO reload scripts and validate compatibility
  }
}
