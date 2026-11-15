package net.firedevops.firemud.recovery.journal;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Stable JSON and digest encoding for the operation-owned immutable tuples. */
@Component
public final class InitialGameplayReleaseTupleCodec {
  private final ObjectMapper objectMapper;

  public InitialGameplayReleaseTupleCodec(ObjectMapper objectMapper) {
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper must not be null").rebuild().build();
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
      canonical = Rfc8785CanonicalJson.canonicalizeUtf8(toJson(value));
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

  public String operationTupleDigest(
      Scope scope,
      java.util.UUID maintenanceLockIdentity,
      InitialGameplayReleaseModel.InitialGameplayReleaseEvidence evidence) {
    return digest(new OperationProofTuple(scope, maintenanceLockIdentity, evidence));
  }

  public record OperationProofTuple(
      Scope scope,
      java.util.UUID maintenanceLockIdentity,
      InitialGameplayReleaseModel.InitialGameplayReleaseEvidence evidence) {}

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
      return objectMapper.readValue(
          json, InitialGameplayReleaseModel.InitialGameplayReleaseEvidence.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Unable to read persisted initial gameplay release evidence", exception);
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
