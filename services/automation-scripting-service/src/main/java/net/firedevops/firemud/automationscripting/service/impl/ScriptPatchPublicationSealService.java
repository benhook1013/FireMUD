package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.RequestIdValidation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds and stores the owner-local immutable script patch snapshot.
 *
 * <p>This unregistered primitive stores content and exact request binding only. It does not
 * authenticate Game Design, produce an affected-handler readiness manifest, or authorize
 * publication. The caller must own the transaction; the storage primitive verifies that physical
 * transaction exists.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repositories and mapper are internal Spring collaborators.")
public class ScriptPatchPublicationSealService {
  private static final int MANIFEST_SCHEMA_VERSION = 1;
  private static final int MAX_SCRIPT_PATCH_VERSION_LENGTH = 20;

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
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public ScriptPatchPublicationSeal sealSnapshot(
      PublicationDigestRequestBinding publication, String workflowIdentity, String requestDigest) {
    if (publication == null
        || publication.scopeKind() != PublicationDigestRequestBinding.ScopeKind.SCRIPT_PATCH) {
      throw new IllegalArgumentException("script patch publication binding required");
    }
    publication.validateSupplied(workflowIdentity, requestDigest);
    String tenantId = publication.tenantId();
    long baseVersionId =
        RequestIdValidation.requirePositiveLong(publication.baseVersionId(), "baseVersionId");
    String scriptPatchVersion = publication.scriptPatchVersion();
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    validateText(scriptPatchVersion, MAX_SCRIPT_PATCH_VERSION_LENGTH, "scriptPatchVersion");

    seals.lockForUpdate(tenantKey, scriptPatchVersion);
    if (!definitions
        .findScriptPatchBaseVersionId(tenantId, scriptPatchVersion)
        .filter(base -> base == baseVersionId)
        .isPresent()) {
      throw new IllegalStateException("script_patch_retained_base_missing_or_changed");
    }
    List<ScriptDefinition> scriptSnapshot =
        definitions.findByTenantIdAndScriptVersionOrderByNameAsc(tenantKey, scriptPatchVersion);
    List<ScriptEventBinding> bindingSnapshot =
        bindings
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                tenantKey, scriptPatchVersion);
    validateDefinitions(scriptSnapshot);

    ScriptDesignDigestService.ScriptDraftDesignDigest digest =
        digestService.getDraftDesignDigestForScriptPatchSnapshot(
            Long.toString(tenantKey),
            baseVersionId,
            scriptPatchVersion,
            scriptSnapshot,
            bindingSnapshot);
    String snapshotJson =
        serializeSnapshot(
            tenantKey,
            baseVersionId,
            scriptPatchVersion,
            scriptSnapshot,
            bindingSnapshot,
            digest,
            publication);

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
      long baseVersionId,
      String scriptPatchVersion,
      List<ScriptDefinition> scriptSnapshot,
      List<ScriptEventBinding> bindingSnapshot,
      ScriptDesignDigestService.ScriptDraftDesignDigest digest,
      PublicationDigestRequestBinding publication) {
    List<Map<String, Object>> scriptEntries =
        scriptSnapshot.stream()
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
                            Map.entry("requiresExclusiveEvent", binding.isRequiresExclusiveEvent()),
                            Map.entry("enabled", binding.isEnabled()))))
            .toList();
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
                Map.entry("publishRequestId", publication.publishRequestId()),
                Map.entry("derivedWorkflowIdentity", publication.derivedWorkflowIdentity()),
                Map.entry("requestDigest", publication.requestDigest())));
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
