package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories and mapper are internal Spring collaborators.")
public class ScriptDesignDigestServiceImpl implements ScriptDesignDigestService {
  private static final int DIGEST_SCHEMA_VERSION = 4;
  private static final Comparator<String> NULLS_FIRST_STRING =
      Comparator.nullsFirst(String::compareTo);
  private static final Comparator<ScriptEventBinding> BINDING_DIGEST_ORDER =
      Comparator.comparing(ScriptEventBinding::getScriptPatchVersion, NULLS_FIRST_STRING)
          .thenComparing(ScriptEventBinding::getEventType, NULLS_FIRST_STRING)
          .thenComparing(ScriptEventBinding::getEventSchemaVersion, NULLS_FIRST_STRING)
          .thenComparing(ScriptEventBinding::getScriptId, NULLS_FIRST_STRING)
          .thenComparing(ScriptEventBinding::getTargetScopeType, NULLS_FIRST_STRING)
          .thenComparing(ScriptEventBinding::getTargetScopeId, NULLS_FIRST_STRING)
          .thenComparingInt(ScriptEventBinding::getPriority)
          .thenComparing(ScriptEventBinding::getPriorityTag, NULLS_FIRST_STRING)
          .thenComparing(ScriptEventBinding::isRequiresExclusiveEvent)
          .thenComparing(ScriptEventBinding::isEnabled)
          .thenComparing(ScriptEventBinding::getBindingId, NULLS_FIRST_STRING);

  private final ScriptDefinitionRepository repository;
  private final ScriptEventBindingRepository bindingRepository;
  private final ObjectMapper objectMapper;

  public ScriptDesignDigestServiceImpl(
      ScriptDefinitionRepository repository,
      ScriptEventBindingRepository bindingRepository,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.bindingRepository = bindingRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public ScriptDraftDesignDigest getDraftDesignDigestForVersion(String tenantId, String versionId) {
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    List<Map<String, Object>> scripts =
        repository.findByTenantIdOrderByNameAscScriptVersionAsc(tenantKey).stream()
            .map(
                script ->
                    canonicalMap(
                        Map.of(
                            "name", normalize(script.getName()),
                            "version", normalize(script.getScriptVersion()),
                            "definition", normalize(script.getDefinition()))))
            .toList();
    List<Map<String, Object>> bindings =
        bindingRepository
            .findByTenantIdOrderByScriptPatchVersionAscEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                tenantKey)
            .stream()
            .sorted(BINDING_DIGEST_ORDER)
            .map(this::bindingDigest)
            .toList();
    try {
      String canonicalJson =
          objectMapper.writeValueAsString(
              canonicalMap(
                  Map.of(
                      "tenantId", tenantId,
                      "versionId", versionId,
                      "scripts", scripts,
                      "eventBindings", bindings)));
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
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    List<Map<String, Object>> scripts =
        repository
            .findByTenantIdAndScriptVersionOrderByNameAsc(tenantKey, scriptPatchVersion)
            .stream()
            .map(
                script ->
                    canonicalMap(
                        Map.of(
                            "name", normalize(script.getName()),
                            "version", normalize(script.getScriptVersion()),
                            "definition", normalize(script.getDefinition()))))
            .toList();
    List<Map<String, Object>> bindings =
        bindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                tenantKey, scriptPatchVersion)
            .stream()
            .sorted(BINDING_DIGEST_ORDER)
            .map(this::bindingDigest)
            .toList();
    if (scripts.isEmpty()) {
      throw new IllegalArgumentException("script patch version not found");
    }
    try {
      String canonicalJson =
          objectMapper.writeValueAsString(
              canonicalMap(
                  Map.of(
                      "tenantId", tenantId,
                      "scriptPatchVersion", scriptPatchVersion,
                      "scripts", scripts,
                      "eventBindings", bindings)));
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

  private Map<String, Object> bindingDigest(ScriptEventBinding binding) {
    return canonicalMap(
        Map.ofEntries(
            Map.entry("scriptPatchVersion", normalize(binding.getScriptPatchVersion())),
            Map.entry("eventType", normalize(binding.getEventType())),
            Map.entry("eventSchemaVersion", normalize(binding.getEventSchemaVersion())),
            Map.entry("scriptId", normalize(binding.getScriptId())),
            Map.entry("bindingId", normalize(binding.getBindingId())),
            Map.entry("targetScopeType", normalize(binding.getTargetScopeType())),
            Map.entry("targetScopeId", normalize(binding.getTargetScopeId())),
            Map.entry("priority", binding.getPriority()),
            Map.entry("priorityTag", normalize(binding.getPriorityTag())),
            Map.entry("requiresExclusiveEvent", binding.isRequiresExclusiveEvent()),
            Map.entry("enabled", binding.isEnabled())));
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  // canonicalMap sorts top-level keys; nested determinism relies on already-canonical structures.
  private static Map<String, Object> canonicalMap(Map<String, Object> values) {
    return new TreeMap<>(values);
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
