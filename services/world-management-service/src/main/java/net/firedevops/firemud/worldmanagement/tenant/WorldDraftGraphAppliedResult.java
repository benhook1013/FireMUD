package net.firedevops.firemud.worldmanagement.tenant;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.ObjectMapper;

/**
 * Immutable graph/Account/complete-epoch carrier; only the atomic application writer creates it.
 */
public final class WorldDraftGraphAppliedResult {
  private final WorldDraftGraphApplication application;
  private final byte[] graphBytes;
  private final byte[] canonicalBytes;

  private WorldDraftGraphAppliedResult(WorldDraftGraphApplication application, byte[] graphBytes) {
    this.application = Objects.requireNonNull(application, "application");
    this.graphBytes = Objects.requireNonNull(graphBytes, "graphBytes").clone();
    new WorldCanonicalAuthoredGraphReader().read(application.plan(), this.graphBytes);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schema", "world-draft-graph-applied/v1");
    result.put("status", "APPLIED");
    result.put("operationBytesBase64", base64(application.operation().canonicalBytes()));
    result.put("graphBytesBase64", base64(this.graphBytes));
    result.put("graphDigest", digest(this.graphBytes));
    result.put("appliedEpochs", appliedEpochs());
    try {
      canonicalBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(new ObjectMapper().writeValueAsString(result));
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException("World applied result cannot be encoded", exception);
    }
  }

  static WorldDraftGraphAppliedResult create(WorldDraftGraphApplication application, byte[] graph) {
    return new WorldDraftGraphAppliedResult(application, graph);
  }

  static WorldDraftGraphAppliedResult fromStored(
      WorldDraftGraphApplication application, byte[] graph, byte[] bytes, String storedDigest) {
    var result = create(application, graph);
    if (!Arrays.equals(result.canonicalBytes, bytes) || !result.digest().equals(storedDigest)) {
      throw new IllegalArgumentException(
          "World APPLIED result differs from exact operation, graph or epochs");
    }
    return result;
  }

  public WorldDraftGraphApplication application() {
    return application;
  }

  public byte[] graphBytes() {
    return graphBytes.clone();
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest(canonicalBytes);
  }

  public String resultIdentity() {
    return "world-draft-graph-applied/v1:" + application.operation().operationId();
  }

  public String status() {
    return "APPLIED";
  }

  /**
   * Original local outcome for a future authenticated recovery reader; no fence release authority.
   */
  public DraftAuthorizationFenceBinding.OwnerReadback ownerReadback() {
    var operation = application.operation();
    var readback =
        new DraftAuthorizationFenceBinding.OwnerReadback(
            DraftAuthorizationFenceBinding.Owner.WORLD,
            DraftAuthorizationFenceBinding.Outcome.COMMITTED,
            operation.operationId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.binding().digest(),
            operation.accountBindingBytes(),
            canonicalBytes());
    readback.requireBinding(
        DraftAuthorizationFenceBinding.fromStored(operation.accountBindingBytes()));
    return readback;
  }

  public List<AppliedEpoch> appliedEpochs() {
    return application.operation().binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
        .map(
            unit ->
                new AppliedEpoch(
                    unit.aggregateType(),
                    unit.aggregateId(),
                    unit.scopeType(),
                    unit.scopeId(),
                    unit.expectedEpoch(),
                    new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
        .toList();
  }

  private static String base64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
