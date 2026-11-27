package net.firedevops.firemud.recovery.journal;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TypedEffect;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Stable JSON and digest encoding for the operation-owned immutable tuples. */
@Component
public final class InitialGameplayReleaseTupleCodec {
  private final ObjectMapper objectMapper;
  private final tools.jackson.databind.ObjectWriter digestMapper;

  public InitialGameplayReleaseTupleCodec() {
    // Retained payloads and digests use the journal schema independently of Spring JSON settings.
    this.objectMapper =
        JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(
                LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Textual,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();
    this.digestMapper = this.objectMapper.writer().with(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS);
  }

  public String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to encode initial gameplay release tuple", exception);
    }
  }

  public String digest(Object value) {
    final byte[] canonical;
    try {
      // Digest encoding preserves full-width integer identities instead of JCS binary64 rounding.
      canonical =
          Rfc8785CanonicalJson.canonicalizeStrictUtf8(
              digestMapper.writeValueAsString(
                  new DigestTuple("initial-gameplay-release-tuple/v2", value)));
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Initial gameplay release tuple is not canonical JSON", exception);
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record DigestTuple(String schema, Object value) {}

  public void requireEffectMatchesOperation(TypedEffect effect, Operation operation) {
    if (!effect.scope().equals(operation.scope())
        || !effect.maintenanceLockIdentity().equals(operation.maintenanceLockIdentity())
        || operation.evidence() == null
        || !effect.evidenceIdentity().equals(operation.evidence().evidenceIdentity())
        || !effect.operationTupleDigest().equals(operationTupleDigest(operation))) {
      throw new InitialGameplayReleaseConflictException(
          "IDEMPOTENCY_CONFLICT: retained effect does not match the operation-owned tuple");
    }
  }

  public String operationTupleDigest(Operation operation) {
    return operationTupleDigest(
        operation.scope(), operation.maintenanceLockIdentity(), operation.evidence());
  }

  public String operationTupleDigest(
      Scope scope,
      java.util.UUID maintenanceLockIdentity,
      InitialGameplayReleaseModel.InitialGameplayReleaseEvidence evidence) {
    return digest(new OperationProofTuple(scope, maintenanceLockIdentity, evidence));
  }

  public record OperationProofTuple(
      Scope scope,
      java.util.UUID maintenanceLockIdentity,
      InitialGameplayReleaseModel.InitialGameplayReleaseEvidence evidence) {
    @Override
    public String toString() {
      return "OperationProofTuple[scope="
          + scope
          + ", maintenanceLockIdentity=<redacted>, evidence="
          + evidence
          + "]";
    }
  }

  public InitialGameplayReleaseModel.Scope readScope(String json) {
    try {
      return objectMapper.readValue(json, InitialGameplayReleaseModel.Scope.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Unable to read persisted initial gameplay release scope", exception);
    }
  }

  public InitialGameplayReleaseModel.PeerStorageBinding readPeerStorageBinding(String json) {
    try {
      return objectMapper.readValue(json, InitialGameplayReleaseModel.PeerStorageBinding.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to read persisted peer/storage binding", exception);
    }
  }

  public InitialGameplayReleaseModel.InitialGameplayReleaseEvidence readEvidence(String json) {
    try {
      JsonNode persisted = objectMapper.readTree(json);
      JsonNode persistedCapacity = persisted.path("issuerLayout").path("capacityVector");
      requireCanonicalCapacityVectorKeys(persistedCapacity);
      var evidence =
          objectMapper.treeToValue(
              persisted, InitialGameplayReleaseModel.InitialGameplayReleaseEvidence.class);
      if (evidence != null
          && !hasCanonicalJsonShape(persistedCapacity, evidence.issuerLayout().capacityVector())) {
        throw new IllegalStateException(
            "Persisted initial gameplay release evidence has a noncanonical shape");
      }
      return evidence;
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Unable to read persisted initial gameplay release evidence", exception);
    }
  }

  private static void requireCanonicalCapacityVectorKeys(JsonNode capacityVector) {
    if (!capacityVector.isObject()) return;
    for (String key : capacityVector.propertyNames()) {
      if (!(key.equals("0") || key.matches("[1-9][0-9]*"))) {
        throw new IllegalStateException(
            "Persisted initial gameplay release evidence has a noncanonical shape: "
                + "capacity vector key is not a canonical decimal integer");
      }
      try {
        Integer.parseInt(key);
      } catch (NumberFormatException exception) {
        throw new IllegalStateException(
            "Persisted initial gameplay release evidence has a noncanonical shape: "
                + "capacity vector key is outside the integer range",
            exception);
      }
    }
  }

  InitialGameplayReleaseModel.PublicTransitionTuple readPublicTransition(String json) {
    try {
      var tuple =
          objectMapper.readValue(json, InitialGameplayReleaseModel.PublicTransitionTuple.class);
      // JSONB may reorder object properties, but stored fields and JSON value types must be exact.
      if (tuple == null || !hasCanonicalJsonShape(json, tuple)) {
        throw new IllegalStateException("Persisted public transition has a noncanonical shape");
      }
      return tuple;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Unable to read persisted public transition", exception);
    }
  }

  private boolean hasCanonicalJsonShape(String json, Object value) throws JacksonException {
    return hasCanonicalJsonShape(objectMapper.readTree(json), value);
  }

  private boolean hasCanonicalJsonShape(JsonNode persisted, Object value) {
    return persisted.equals(objectMapper.valueToTree(value));
  }

  InitialGameplayReleaseModel.EffectReadback readEffectReadback(String json) {
    try {
      JsonNode persisted = objectMapper.readTree(json);
      JsonNode identity = persisted == null ? null : persisted.get("readbackIdentity");
      if (identity == null || !identity.isTextual()) {
        throw new IllegalStateException("Persisted effect readback identity must be text");
      }
      var readback =
          objectMapper.treeToValue(persisted, InitialGameplayReleaseModel.EffectReadback.class);
      if (readback == null) {
        throw new IllegalStateException("Persisted initial gameplay release readback is null");
      }
      return readback;
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Unable to read persisted initial gameplay release effect readback", exception);
    }
  }

  public InitialGameplayReleaseModel.TypedEffect readEffect(String kind, String json) {
    try {
      if (InitialGameplayReleaseModel.EffectKind.PREPARE_PLAYABLE_STATE_FENCED
          == InitialGameplayReleaseModel.EffectKind.fromDatabaseValue(kind)) {
        return objectMapper.readValue(
            json, InitialGameplayReleaseModel.PreparePlayableStateFencedEffect.class);
      }
      return objectMapper.readValue(
          json, InitialGameplayReleaseModel.CommitOwnerAuthorizedHandoffEffect.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Unable to read persisted initial gameplay release effect", exception);
    }
  }
}
