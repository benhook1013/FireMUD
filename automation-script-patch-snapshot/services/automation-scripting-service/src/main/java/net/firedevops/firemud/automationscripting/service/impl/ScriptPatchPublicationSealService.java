package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.model.ScriptPatchPublicationSeal;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptPatchPublicationSealRepository;
import net.firedevops.firemud.automationscripting.service.ScriptDesignDigestService;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds and stores the owner-local immutable script patch snapshot.
 *
 * <p>This is deliberately not bound to an RPC. A public publication operation must additionally
 * authenticate the Game Design workload and bind the complete publishRequestId, workflow identity,
 * and requestDigest before this can become a publication-authority boundary.
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories and mapper are internal Spring collaborators.")
public class ScriptPatchPublicationSealService {
  private static final int MANIFEST_SCHEMA_VERSION = 1;
  private static final int MAX_SCRIPT_PATCH_VERSION_LENGTH = 20;
  private static final int MAX_BASE_VERSION_ID_LENGTH = 128;

  private final ScriptDefinitionRepository definitions;
  private final ScriptEventBindingRepository bindings;
  private final ScriptPatchPublicationSealRepository seals;
  private final ScriptDesignDigestService digestService;
  private final ObjectMapper objectMapper;

  public ScriptPatchPublicationSealService(
      ScriptDefinitionRepository definitions,
      ScriptEventBindingRepository bindings,
      ScriptPatchPublicationSealRepository seals,
      ScriptDesignDigestService digestService,
      ObjectMapper objectMapper) {
    this.definitions = definitions;
    this.bindings = bindings;
    this.seals = seals;
    this.digestService = digestService;
    this.objectMapper = objectMapper;
  }

  /**
   * Stores one canonical snapshot for the exact tenant/base/patch identity. An identical reseal is
   * idempotent; any changed definition, binding, schema, digest, or base binding is rejected.
   */
  @Transactional
  public ScriptPatchPublicationSeal sealSnapshot(
      String tenantId, String baseVersionId, String scriptPatchVersion) {
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    validateText(baseVersionId, MAX_BASE_VERSION_ID_LENGTH, "baseVersionId");
    validateText(scriptPatchVersion, MAX_SCRIPT_PATCH_VERSION_LENGTH, "scriptPatchVersion");

    seals.lockForUpdate(tenantKey, scriptPatchVersion);
    List<ScriptDefinition> scriptSnapshot =
        definitions.findByTenantIdAndScriptVersionOrderByNameAsc(tenantKey, scriptPatchVersion);
    List<ScriptEventBinding> bindingSnapshot =
        bindings
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                tenantKey, scriptPatchVersion);
    validateDefinitions(scriptSnapshot);

    ScriptDesignDigestService.ScriptDraftDesignDigest digest =
        digestService.getDraftDesignDigestForScriptPatchSnapshot(
            Long.toString(tenantKey), scriptPatchVersion, scriptSnapshot, bindingSnapshot);
    String snapshotJson =
        serializeSnapshot(
            tenantKey, baseVersionId, scriptPatchVersion, scriptSnapshot, bindingSnapshot, digest);

    // The repository reacquires the same transaction lock and allows only byte-for-byte resealing.
    return seals.seal(
        tenantKey,
        baseVersionId,
        scriptPatchVersion,
        MANIFEST_SCHEMA_VERSION,
        digest.digestSchemaVersion(),
        digest.contentDigest(),
        snapshotJson);
  }

  private String serializeSnapshot(
      long tenantId,
      String baseVersionId,
      String scriptPatchVersion,
      List<ScriptDefinition> scriptSnapshot,
      List<ScriptEventBinding> bindingSnapshot,
      ScriptDesignDigestService.ScriptDraftDesignDigest digest) {
    List<Map<String, Object>> scriptEntries =
        scriptSnapshot.stream()
            .sorted(Comparator.comparing(ScriptDefinition::getName))
            .map(
                definition ->
                    canonicalMap(
                        Map.of(
                            "scriptName", definition.getName(),
                            "scriptPatchVersion", definition.getScriptVersion(),
                            "definition", definition.getDefinition())))
            .toList();
    List<Map<String, Object>> bindingEntries =
        bindingSnapshot.stream()
            .map(
                binding ->
                    canonicalMap(
                        Map.ofEntries(
                            Map.entry("scriptPatchVersion", binding.getScriptPatchVersion()),
                            Map.entry("eventType", binding.getEventType()),
                            Map.entry("eventSchemaVersion", binding.getEventSchemaVersion()),
                            Map.entry("scriptId", binding.getScriptId()),
                            Map.entry("bindingId", binding.getBindingId()),
                            Map.entry("targetScopeType", binding.getTargetScopeType()),
                            Map.entry("targetScopeId", binding.getTargetScopeId()),
                            Map.entry("priority", binding.getPriority()),
                            Map.entry("priorityTag", binding.getPriorityTag()),
                            Map.entry(
                                "requiresExclusiveEvent", binding.isRequiresExclusiveEvent()),
                            Map.entry("enabled", binding.isEnabled()))))
            .toList();
    List<String> onLoadHandlerIds =
        scriptSnapshot.stream().map(ScriptDefinition::getName).sorted().toList();
    Map<String, Object> envelope =
        canonicalMap(
            Map.ofEntries(
                Map.entry("tenantId", Long.toString(tenantId)),
                Map.entry("baseVersionId", baseVersionId),
                Map.entry("scriptPatchVersion", scriptPatchVersion),
                Map.entry("manifestSchemaVersion", MANIFEST_SCHEMA_VERSION),
                Map.entry("contentDigestSchemaVersion", digest.digestSchemaVersion()),
                Map.entry("contentDigest", digest.contentDigest()),
                Map.entry("definitions", scriptEntries),
                Map.entry("eventBindings", bindingEntries),
                Map.entry("onLoadHandlerIds", onLoadHandlerIds)));
    try {
      return objectMapper.writeValueAsString(envelope);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to serialize script patch publication snapshot", ex);
    }
  }

  private static void validateDefinitions(List<ScriptDefinition> scriptSnapshot) {
    String previousName = null;
    for (ScriptDefinition definition : scriptSnapshot) {
      if (definition.getName() == null
          || definition.getName().isBlank()
          || definition.getDefinition() == null
          || definition.getScriptVersion() == null) {
        throw new IllegalStateException("script_patch_publication_definition_invalid");
      }
      if (definition.getName().equals(previousName)) {
        throw new IllegalStateException("script_patch_publication_definition_identity_duplicate");
      }
      previousName = definition.getName();
    }
  }

  private static void validateText(String value, int maximumLength, String field) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static Map<String, Object> canonicalMap(Map<String, Object> values) {
    return new TreeMap<>(values);
  }
}
