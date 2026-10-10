package net.firedevops.firemud.common.operator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable shared value and canonical codec for ADR 0048's StartSession {@code
 * preAuthorizationReservationTuple/v1}.
 *
 * <p>This value describes tuple contents only. Constructing or rehydrating it does not establish
 * authenticated actor authority, reservation ownership, a claim, or permission to dispatch.
 */
public final class StartSessionPreAuthorizationReservationTuple {
  public static final String TUPLE_SCHEMA_ID = "preAuthorizationReservationTuple";
  public static final String TUPLE_SCHEMA_VERSION = "1";
  public static final int MAX_CONTROL_PLANE_REQUEST_ID_UTF8_BYTES = 128;
  private static final int MAX_CANONICAL_TUPLE_UTF8_BYTES = 8 * 1_024;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "tupleSchemaId",
          "tupleSchemaVersion",
          "actionFamilyRequestIdentity",
          "actor",
          "automationPolicy",
          "actionFamily",
          "actionFamilySchemaId",
          "actionFamilySchemaVersion",
          "scope",
          "target",
          "targetOwner",
          "expectedVersion",
          "mutation",
          "auditReason");

  private final String controlPlaneRequestId;
  private final HumanActor actor;
  private final StartSessionOperatorAction action;

  private StartSessionPreAuthorizationReservationTuple(
      String controlPlaneRequestId, HumanActor actor, StartSessionOperatorAction action) {
    this.controlPlaneRequestId = requireCanonicalControlPlaneRequestId(controlPlaneRequestId);
    this.actor = Objects.requireNonNull(actor, "actor is required");
    this.action = Objects.requireNonNull(action, "action is required");
  }

  /** Creates tuple contents from typed values; the actor argument is not authenticated evidence. */
  public static StartSessionPreAuthorizationReservationTuple createHuman(
      String controlPlaneRequestId, UUID actorAccountId, StartSessionOperatorAction action) {
    return new StartSessionPreAuthorizationReservationTuple(
        controlPlaneRequestId, new HumanActor(actorAccountId), action);
  }

  /** Rehydrates the exact canonical ADR 0048 tuple value. This does not establish authority. */
  public static StartSessionPreAuthorizationReservationTuple fromCanonicalJson(String json) {
    Objects.requireNonNull(json, "tuple JSON is required");
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_TUPLE_UTF8_BYTES) {
      throw new IllegalArgumentException("pre-authorization tuple exceeds its byte limit");
    }
    try {
      String canonical =
          new String(Rfc8785CanonicalJson.canonicalizeUtf8(json), StandardCharsets.UTF_8);
      if (!canonical.equals(json)) {
        throw new IllegalArgumentException("pre-authorization tuple is not canonical JSON");
      }
      Object decoded = JSON.readValue(json, Object.class);
      Map<String, Object> root = requireObject(decoded, "tuple");
      requireExactFields(root, ROOT_FIELDS, "tuple");
      if (!TUPLE_SCHEMA_ID.equals(requireString(root.get("tupleSchemaId"), "tupleSchemaId"))
          || !TUPLE_SCHEMA_VERSION.equals(
              requireString(root.get("tupleSchemaVersion"), "tupleSchemaVersion"))) {
        throw new IllegalArgumentException("unsupported pre-authorization tuple schema");
      }

      Map<String, Object> identity =
          requireObject(root.get("actionFamilyRequestIdentity"), "actionFamilyRequestIdentity");
      requireExactFields(
          identity, Set.of("requestIdentityKind", "requestId"), "actionFamilyRequestIdentity");
      if (!"controlPlaneRequestId"
          .equals(requireString(identity.get("requestIdentityKind"), "requestIdentityKind"))) {
        throw new IllegalArgumentException(
            "StartSession request identity must be controlPlaneRequestId");
      }
      String controlPlaneRequestId = requireString(identity.get("requestId"), "requestId");

      Map<String, Object> actorObject = requireObject(root.get("actor"), "actor");
      requireExactFields(actorObject, Set.of("kind", "accountId"), "actor");
      if (!"HUMAN".equals(requireString(actorObject.get("kind"), "actor.kind"))) {
        throw new IllegalArgumentException("StartSession reservation actor must be human");
      }
      HumanActor actor =
          new HumanActor(requireCanonicalUuid(actorObject.get("accountId"), "actor.accountId"));

      Map<String, Object> automation =
          requireObject(root.get("automationPolicy"), "automationPolicy");
      requireExactFields(automation, Set.of("presence"), "automationPolicy");
      if (!"ABSENT"
          .equals(requireString(automation.get("presence"), "automationPolicy.presence"))) {
        throw new IllegalArgumentException(
            "human StartSession tuple must explicitly omit automation policy");
      }

      String actionFamily = requireString(root.get("actionFamily"), "actionFamily");
      String schemaId = requireString(root.get("actionFamilySchemaId"), "actionFamilySchemaId");
      String schemaVersion =
          requireString(root.get("actionFamilySchemaVersion"), "actionFamilySchemaVersion");
      if (!StartSessionOperatorAction.ACTION_FAMILY.equals(actionFamily)
          || !StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID.equals(schemaId)
          || !StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION.equals(schemaVersion)) {
        throw new IllegalArgumentException("unsupported StartSession action-family schema");
      }

      Map<String, Object> scopeObject = requireObject(root.get("scope"), "scope");
      requireExactFields(scopeObject, Set.of("tenantId", "targetNamespace"), "scope");
      StartSessionOperatorAction.Scope scope =
          new StartSessionOperatorAction.Scope(
              requireCanonicalUuid(scopeObject.get("tenantId"), "scope.tenantId"),
              requireString(scopeObject.get("targetNamespace"), "scope.targetNamespace"));

      Map<String, Object> targetObject = requireObject(root.get("target"), "target");
      requireExactFields(targetObject, Set.of("gameTemplateId", "ownerAccountId"), "target");
      StartSessionOperatorAction.Target target =
          new StartSessionOperatorAction.Target(
              requirePositiveCanonicalLong(targetObject.get("gameTemplateId")),
              requireCanonicalUuid(targetObject.get("ownerAccountId"), "target.ownerAccountId"));

      Map<String, Object> ownerObject = requireObject(root.get("targetOwner"), "targetOwner");
      requireExactFields(ownerObject, Set.of("ownerService"), "targetOwner");
      if (!StartSessionOperatorAction.OWNER_SERVICE.equals(
          requireString(ownerObject.get("ownerService"), "targetOwner.ownerService"))) {
        throw new IllegalArgumentException("StartSession targetOwner is immutable and unsupported");
      }

      Map<String, Object> expectedVersion =
          requireObject(root.get("expectedVersion"), "expectedVersion");
      requireExactFields(expectedVersion, Set.of("presence"), "expectedVersion");
      if (!"ABSENT"
          .equals(requireString(expectedVersion.get("presence"), "expectedVersion.presence"))) {
        throw new IllegalArgumentException(
            "StartSession expectedVersion must be explicitly absent");
      }

      Map<String, Object> mutationObject = requireObject(root.get("mutation"), "mutation");
      requireExactFields(mutationObject, Set.of("clientIp"), "mutation");
      Map<String, Object> clientIpObject =
          requireObject(mutationObject.get("clientIp"), "mutation.clientIp");
      String clientIpPresence =
          requireString(clientIpObject.get("presence"), "mutation.clientIp.presence");
      StartSessionOperatorAction.ClientIp clientIp;
      if ("ABSENT".equals(clientIpPresence)) {
        requireExactFields(clientIpObject, Set.of("presence"), "mutation.clientIp");
        clientIp = StartSessionOperatorAction.ClientIp.absent();
      } else if ("PRESENT".equals(clientIpPresence)) {
        requireExactFields(clientIpObject, Set.of("presence", "value"), "mutation.clientIp");
        clientIp =
            StartSessionOperatorAction.ClientIp.of(
                requireString(clientIpObject.get("value"), "mutation.clientIp.value"));
      } else {
        throw new IllegalArgumentException("mutation.clientIp presence must be ABSENT or PRESENT");
      }

      StartSessionOperatorAction action =
          new StartSessionOperatorAction(
              schemaId,
              schemaVersion,
              scope,
              target,
              StartSessionOperatorAction.ExpectedVersion.ABSENT,
              new StartSessionOperatorAction.Mutation(clientIp),
              requireString(root.get("auditReason"), "auditReason"));
      StartSessionPreAuthorizationReservationTuple tuple =
          new StartSessionPreAuthorizationReservationTuple(controlPlaneRequestId, actor, action);
      if (!tuple.canonicalJson().equals(json)) {
        throw new IllegalArgumentException("pre-authorization tuple shape is not canonical");
      }
      return tuple;
    } catch (IOException exception) {
      throw new IllegalArgumentException("pre-authorization tuple JSON is invalid", exception);
    }
  }

  public String actionFamily() {
    return action.actionFamily();
  }

  public String controlPlaneRequestId() {
    return controlPlaneRequestId;
  }

  public HumanActor actor() {
    return actor;
  }

  public NoAutomationPolicy automationPolicy() {
    return NoAutomationPolicy.ABSENT;
  }

  public StartSessionOperatorAction action() {
    return action;
  }

  /** Validate a stable request key without silently normalizing or changing its identity. */
  public static String requireCanonicalControlPlaneRequestId(String value) {
    Objects.requireNonNull(value, "controlPlaneRequestId is required");
    if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
      throw new IllegalArgumentException("controlPlaneRequestId must already be NFC-normalized");
    }
    return requireBoundedText(value, "controlPlaneRequestId");
  }

  public String targetOwner() {
    return action.targetOwner();
  }

  /** Returns ADR 0047's digest over the seven action-schema grammar fields. */
  public String mutationDigest() {
    return StartSessionOperatorActionCodec.mutationDigest(action);
  }

  /** Returns the complete canonical typed tuple, including explicit absence values. */
  public String canonicalJson() {
    Map<String, Object> scope =
        Map.of(
            "tenantId", action.scope().tenantId().toString(),
            "targetNamespace", action.scope().targetNamespace());
    Map<String, Object> target =
        Map.of(
            "gameTemplateId", Long.toString(action.target().gameTemplateId()),
            "ownerAccountId", action.target().ownerAccountId().toString());
    Map<String, Object> clientIp =
        action.mutation().clientIp() instanceof StartSessionOperatorAction.AbsentClientIp
            ? Map.of("presence", "ABSENT")
            : Map.of(
                "presence",
                "PRESENT",
                "value",
                ((StartSessionOperatorAction.StringClientIp) action.mutation().clientIp()).value());
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("tupleSchemaId", TUPLE_SCHEMA_ID);
    root.put("tupleSchemaVersion", TUPLE_SCHEMA_VERSION);
    root.put(
        "actionFamilyRequestIdentity",
        Map.of("requestIdentityKind", "controlPlaneRequestId", "requestId", controlPlaneRequestId));
    root.put("actor", Map.of("kind", "HUMAN", "accountId", actor.accountId().toString()));
    root.put("automationPolicy", Map.of("presence", automationPolicy().name()));
    root.put("actionFamily", action.actionFamily());
    root.put("actionFamilySchemaId", action.actionFamilySchemaId());
    root.put("actionFamilySchemaVersion", action.actionFamilySchemaVersion());
    root.put("scope", scope);
    root.put("target", target);
    root.put("targetOwner", Map.of("ownerService", action.targetOwner()));
    root.put("expectedVersion", Map.of("presence", action.expectedVersion().name()));
    root.put("mutation", Map.of("clientIp", clientIp));
    root.put("auditReason", action.auditReason());
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("could not encode StartSession reservation tuple", exception);
    }
  }

  public record HumanActor(UUID accountId) {
    public HumanActor {
      Objects.requireNonNull(accountId, "actor accountId is required");
      if (accountId.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("actor accountId must not be nil");
      }
    }
  }

  public enum NoAutomationPolicy {
    ABSENT
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof StartSessionPreAuthorizationReservationTuple that)) {
      return false;
    }
    return controlPlaneRequestId.equals(that.controlPlaneRequestId)
        && actor.equals(that.actor)
        && action.equals(that.action);
  }

  @Override
  public int hashCode() {
    return Objects.hash(controlPlaneRequestId, actor, action);
  }

  private static String requireBoundedText(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " is required");
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    if (normalized.isBlank()
        || normalized.codePoints().anyMatch(Character::isISOControl)
        || normalized.getBytes(StandardCharsets.UTF_8).length
            > MAX_CONTROL_PLANE_REQUEST_ID_UTF8_BYTES) {
      throw new IllegalArgumentException(fieldName + " is blank or exceeds its bound");
    }
    for (int index = 0; index < normalized.length(); index++) {
      char current = normalized.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (index + 1 >= normalized.length()
            || !Character.isLowSurrogate(normalized.charAt(index + 1))) {
          throw new IllegalArgumentException(fieldName + " contains malformed Unicode");
        }
        index++;
      } else if (Character.isLowSurrogate(current)) {
        throw new IllegalArgumentException(fieldName + " contains malformed Unicode");
      }
    }
    return normalized;
  }

  private static UUID requireCanonicalUuid(Object value, String fieldName) {
    String text = requireString(value, fieldName);
    UUID parsed;
    try {
      parsed = UUID.fromString(text);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(fieldName + " must be a canonical UUID", exception);
    }
    if (!parsed.toString().equals(text) || parsed.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(fieldName + " must be a canonical non-nil UUID");
    }
    return parsed;
  }

  private static long requirePositiveCanonicalLong(Object value) {
    String text = requireString(value, "target.gameTemplateId");
    if (!text.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "target.gameTemplateId must be a positive canonical integer");
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "target.gameTemplateId exceeds its supported range", exception);
    }
  }

  private static String requireString(Object value, String fieldName) {
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException(fieldName + " must be a string");
    }
    return text;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> requireObject(Object value, String fieldName) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(fieldName + " must be an object");
    }
    for (Object key : map.keySet()) {
      if (!(key instanceof String)) {
        throw new IllegalArgumentException(fieldName + " contains a non-string field name");
      }
    }
    return (Map<String, Object>) map;
  }

  private static void requireExactFields(
      Map<String, Object> object, Set<String> expectedFields, String fieldName) {
    if (!object.keySet().equals(expectedFields)) {
      throw new IllegalArgumentException(fieldName + " has missing or unsupported fields");
    }
  }
}
