package net.firedevops.firemud.gamelogic.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class GameLogicDraftDesignDigestServiceImpl implements GameLogicDraftDesignDigestService {
  private static final int DIGEST_SCHEMA_VERSION = 1;

  private final ObjectMapper objectMapper;

  public GameLogicDraftDesignDigestServiceImpl(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public GameLogicDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId) {
    if (versionId == null || versionId.isBlank()) {
      throw new IllegalArgumentException("version_id is required");
    }
    try {
      String canonicalJson =
          objectMapper.writeValueAsString(
              Map.of(
                  "ownedDraftRuleRows",
                  List.of(),
                  "manifestState",
                  "no-game-logic-owned-version-scoped-rule-data"));
      return new GameLogicDraftDesignDigest(
          tenantId,
          versionId,
          "version:" + versionId,
          sha256(canonicalJson),
          DIGEST_SCHEMA_VERSION);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to compute game logic draft design digest", ex);
    }
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
