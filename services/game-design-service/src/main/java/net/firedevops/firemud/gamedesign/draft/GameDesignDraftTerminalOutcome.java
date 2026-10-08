package net.firedevops.firemud.gamedesign.draft;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable final Game Design owner result for one exact Account operation.
 *
 * <p>A missing outcome is unresolved, never an abort. The retained owner vector is Game Design's
 * internal participant evidence; it is not a substitute for Account's independent World outcome.
 * This record is readback data, not an authorization decision or an authenticated RPC receipt.
 */
public final class GameDesignDraftTerminalOutcome {
  private static final ObjectMapper JSON = new ObjectMapper();

  public enum Result {
    COMMITTED,
    DEFINITIVELY_ABORTED
  }

  private final GameDesignDraftTerminalOperation operation;
  private final Result result;
  private final String ownerResultVectorJson;
  private final byte[] finalEvidenceBytes;
  private final String finalEvidenceDigest;
  private final OffsetDateTime createdAt;

  GameDesignDraftTerminalOutcome(
      GameDesignDraftTerminalOperation operation,
      Result result,
      String ownerResultVectorJson,
      byte[] finalEvidenceBytes,
      String finalEvidenceDigest,
      OffsetDateTime createdAt) {
    this.operation = Objects.requireNonNull(operation, "operation");
    this.result = Objects.requireNonNull(result, "result");
    this.ownerResultVectorJson =
        Objects.requireNonNull(ownerResultVectorJson, "ownerResultVectorJson");
    if (ownerResultVectorJson.isBlank()) {
      throw new IllegalArgumentException(
          "A final outcome requires its complete owner result vector");
    }
    validateOwnerResultVector();
    this.finalEvidenceBytes =
        Objects.requireNonNull(finalEvidenceBytes, "finalEvidenceBytes").clone();
    if (this.finalEvidenceBytes.length == 0) {
      throw new IllegalArgumentException("A final outcome requires exact terminal evidence bytes");
    }
    this.finalEvidenceDigest = Objects.requireNonNull(finalEvidenceDigest, "finalEvidenceDigest");
    if (!finalEvidenceDigest.matches("sha256:[0-9a-f]{64}")
        || !finalEvidenceDigest.equals(
            GameDesignDraftTerminalOperation.sha256(this.finalEvidenceBytes))) {
      throw new IllegalArgumentException(
          "Terminal evidence digest differs from exact evidence bytes");
    }
    if (result == Result.COMMITTED
        && !Arrays.equals(
            this.finalEvidenceBytes, ownerResultVectorJson.getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException(
          "Committed terminal evidence must be the exact synchronized owner result vector");
    }
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public GameDesignDraftTerminalOperation operation() {
    return operation;
  }

  public Result result() {
    return result;
  }

  public DraftAuthorizationFenceBinding.Owner owner() {
    return DraftAuthorizationFenceBinding.Owner.GAME_DESIGN;
  }

  /**
   * Maps this durable Game Design result to Account's existing canonical owner-readback value. This
   * is only a byte-for-byte schema adapter; it does not authenticate the producer or make this
   * otherwise unregistered component an authorization source.
   */
  public DraftAuthorizationFenceBinding.OwnerReadback toOwnerReadback() {
    DraftAuthorizationFenceBinding accountBinding = operation.accountBinding();
    return new DraftAuthorizationFenceBinding.OwnerReadback(
        DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
        DraftAuthorizationFenceBinding.Outcome.valueOf(result.name()),
        accountBinding.operationId(),
        accountBinding.commitId(),
        accountBinding.fenceId(),
        accountBinding.inputDigest(),
        operation.accountBindingBytes(),
        finalEvidenceBytes);
  }

  public String ownerResultVectorJson() {
    return ownerResultVectorJson;
  }

  public byte[] finalEvidenceBytes() {
    return finalEvidenceBytes.clone();
  }

  public String finalEvidenceDigest() {
    return finalEvidenceDigest;
  }

  public OffsetDateTime createdAt() {
    return createdAt;
  }

  public boolean exactlyMatches(GameDesignDraftTerminalOutcome other) {
    return other != null
        && operation.exactlyMatches(other.operation)
        && result == other.result
        && ownerResultVectorJson.equals(other.ownerResultVectorJson)
        && Arrays.equals(finalEvidenceBytes, other.finalEvidenceBytes)
        && finalEvidenceDigest.equals(other.finalEvidenceDigest);
  }

  private void validateOwnerResultVector() {
    try {
      byte[] vectorBytes = ownerResultVectorJson.getBytes(StandardCharsets.UTF_8);
      if (!Arrays.equals(
          vectorBytes, Rfc8785CanonicalJson.canonicalizeUtf8(ownerResultVectorJson))) {
        throw new IllegalArgumentException("Terminal owner result vector is not canonical JSON");
      }
      JsonNode vector = JSON.readTree(ownerResultVectorJson);
      if (vector == null
          || !vector.isArray()
          || vector.size() != operation.gameDesignBinding().requiredOwners().size()) {
        throw new IllegalArgumentException("Terminal owner result vector is incomplete");
      }
      for (int index = 0; index < vector.size(); index++) {
        JsonNode item = vector.get(index);
        Owner expectedOwner = operation.gameDesignBinding().requiredOwners().get(index);
        String expectedStatus = result == Result.COMMITTED ? "APPLIED" : null;
        if (item == null
            || !item.isObject()
            || item.size() != 7
            || !text(item, "owner").equals(expectedOwner.name())
            || !text(item, "commitId").equals(operation.gameDesignBinding().commitId().toString())
            || !text(item, "bindingDigest").equals(operation.gameDesignBinding().digest())
            || (expectedStatus != null && !text(item, "status").equals(expectedStatus))
            || (result == Result.DEFINITIVELY_ABORTED
                && !ListStatus.TERMINAL.contains(text(item, "status")))
            || !item.path("appliedEpochs").isArray()
            || !item.path("resultBytesBase64").isTextual()
            || !item.path("resultIdentity").isTextual()) {
          throw new IllegalArgumentException(
              "Terminal owner result vector differs from its complete commit binding");
        }
      }
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException(
          "Terminal owner result vector is not valid JSON", exception);
    }
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Terminal owner result is missing text field " + field);
    }
    return value.textValue();
  }

  private static final class ListStatus {
    private static final java.util.Set<String> TERMINAL = java.util.Set.of("APPLIED", "REJECTED");
  }
}
