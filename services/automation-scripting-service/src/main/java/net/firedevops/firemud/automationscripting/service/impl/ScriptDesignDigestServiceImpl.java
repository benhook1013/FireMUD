package net.firedevops.firemud.automationscripting.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScriptDesignDigestServiceImpl implements ScriptDesignDigestService {
  private static final int DIGEST_SCHEMA_VERSION = 1;

  private final ScriptDefinitionRepository repository;
  private final ObjectMapper objectMapper;

  public ScriptDesignDigestServiceImpl(
      ScriptDefinitionRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Override
  public ScriptDraftDesignDigest getDraftDesignDigestForVersion(String tenantId, String versionId) {
    long tenantKey = Long.parseLong(tenantId);
    List<Map<String, Object>> scripts =
        repository.findByTenantIdOrderByNameAscScriptVersionAsc(tenantKey).stream()
            .map(
                script ->
                    Map.<String, Object>of(
                        "name", script.getName(),
                        "version", script.getScriptVersion(),
                        "definition", script.getDefinition()))
            .toList();
    try {
      String canonicalJson =
          objectMapper.writeValueAsString(
              Map.of("tenantId", tenantId, "versionId", versionId, "scripts", scripts));
      return new ScriptDraftDesignDigest(
          tenantId,
          versionId,
          "version:" + versionId,
          sha256(canonicalJson),
          DIGEST_SCHEMA_VERSION);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to compute automation script digest", ex);
    }
  }

  @Override
  public ScriptDraftDesignDigest getDraftDesignDigestForScriptPatch(
      String tenantId, String scriptPatchVersion) {
    long tenantKey = Long.parseLong(tenantId);
    List<Map<String, Object>> scripts =
        repository
            .findByTenantIdAndScriptVersionOrderByNameAsc(tenantKey, scriptPatchVersion)
            .stream()
            .map(
                script ->
                    Map.<String, Object>of(
                        "name", script.getName(),
                        "version", script.getScriptVersion(),
                        "definition", script.getDefinition()))
            .toList();
    if (scripts.isEmpty()) {
      throw new IllegalArgumentException("script patch version not found");
    }
    try {
      String canonicalJson =
          objectMapper.writeValueAsString(
              Map.of(
                  "tenantId", tenantId,
                  "scriptPatchVersion", scriptPatchVersion,
                  "scripts", scripts));
      return new ScriptDraftDesignDigest(
          tenantId,
          scriptPatchVersion,
          "script-patch:" + scriptPatchVersion,
          sha256(canonicalJson),
          DIGEST_SCHEMA_VERSION);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to compute automation script digest", ex);
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
