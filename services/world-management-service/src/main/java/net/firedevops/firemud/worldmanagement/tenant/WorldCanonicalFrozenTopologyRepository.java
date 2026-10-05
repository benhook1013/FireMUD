package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly unwired immutable graph/2 journal; historical reads never reenter an owner writer. */
public class WorldCanonicalFrozenTopologyRepository {
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();
  private final DSLContext dsl;
  private final WorldAuthoredGraphSnapshotRepository owner;
  private final WorldDraftTopologyCommitRepository topology;

  @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "No resources or finalizer.")
  public WorldCanonicalFrozenTopologyRepository(
      DSLContext dsl,
      WorldAuthoredGraphSnapshotRepository owner,
      WorldDraftTopologyCommitRepository topology) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.topology = Objects.requireNonNull(topology, "topology");
  }

  /** Absence is UNKNOWN, not abort or permission to reopen an owner operation. */
  public Optional<WorldCanonicalFrozenTopology> readCommitted(Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("Frozen topology readback requires no caller transaction");
    }
    return Optional.ofNullable(find(Objects.requireNonNull(request, "request")));
  }

  WorldCanonicalFrozenTopology capture(Request request) {
    // This seam validates actual PostgreSQL isolation and serializes through the existing V25 row.
    var provenance = owner.lockAndResolve(request.freeze());
    WorldCanonicalFrozenTopology prior = find(request);
    if (prior != null) return prior;
    if (!"FROZEN".equals(provenance.ownerFreezePhase())) {
      throw new ConflictException(
          "New canonical graph capture requires the exact current FROZEN attempt");
    }
    var stored = topology.readUnderFrozenLock(request.plan());
    Record original =
        dsl.resultQuery(
                "SELECT to_jsonb(v)::text AS identity_json, to_jsonb(i)::text AS intake_json "
                    + "FROM world_authored_version_identity v JOIN world_authored_source_intake i "
                    + "ON i.operation_id=v.intake_operation_id WHERE v.operation_id=?",
                request.plan().ownerBinding().versionIdentityOperationId())
            .fetchOne();
    if (original == null)
      throw new ConflictException("Frozen topology has no exact original source identity");
    String identityJson = required(original, "identity_json", String.class);
    String intakeJson = required(original, "intake_json", String.class);
    var identity = topology.verifyOriginalSource(request.plan(), identityJson, intakeJson);
    var graph =
        topology.verifyImmutableBytes(request.plan(), stored.graphBytes(), stored.resultBytes());
    requirePrivateKeys(graph, identity);
    UUID captureId = UUID.randomUUID();
    String freezeJson = JSON.writeValueAsString(request.freeze());
    String ownerJson = JSON.writeValueAsString(request.plan().ownerBinding());
    byte[] result =
        result(
            captureId,
            request,
            identityJson,
            intakeJson,
            stored.graphBytes(),
            stored.resultBytes());
    dsl.execute(
        "INSERT INTO world_canonical_frozen_topology (capture_id,publication_fence,"
            + "request_id,commit_id,version_identity_operation_id,freeze_request_json,owner_binding_json,"
            + "binding_json,binding_digest,identity_json,intake_json,graph_bytes,graph_sha256,"
            + "storage_result_bytes,result_bytes,capture_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        captureId,
        request.freeze().publicationFence(),
        request.plan().binding().requestId(),
        request.plan().binding().commitId(),
        request.plan().ownerBinding().versionIdentityOperationId(),
        freezeJson,
        ownerJson,
        request.plan().binding().canonicalJson(),
        request.plan().binding().digest(),
        identityJson,
        intakeJson,
        stored.graphBytes(),
        WorldAuthoredGraphSnapshotCapture.sha256(stored.graphBytes()),
        stored.resultBytes(),
        result,
        WorldCanonicalFrozenTopology.STATUS);
    WorldCanonicalFrozenTopology readback = find(request);
    if (readback == null
        || !captureId.equals(readback.captureId())
        || !Arrays.equals(result, readback.resultBytes())) {
      throw new ConflictException(
          "Frozen topology differs from exact immutable insertion readback");
    }
    return readback;
  }

  private WorldCanonicalFrozenTopology find(Request supplied) {
    Record row =
        dsl.resultQuery(
                "SELECT * FROM world_canonical_frozen_topology WHERE publication_fence=?",
                supplied.freeze().publicationFence())
            .fetchOne();
    if (row == null) return null;
    String bindingJson = required(row, "binding_json", String.class);
    var binding =
        DraftCommitBinding.fromStored(bindingJson, required(row, "binding_digest", String.class));
    String ownerJson = required(row, "owner_binding_json", String.class);
    var ownerBinding = JSON.readValue(ownerJson, OwnerBinding.class);
    String freezeJson = required(row, "freeze_request_json", String.class);
    var freeze = JSON.readValue(freezeJson, CaptureRequest.class);
    Request retained =
        new Request(WorldDraftTopologyCommitPlan.create(binding, ownerBinding), freeze);
    if (!binding.equals(supplied.plan().binding())
        || !ownerBinding.equals(supplied.plan().ownerBinding())
        || !freeze.equals(supplied.freeze())
        || !bindingJson.equals(binding.canonicalJson())
        || !ownerJson.equals(JSON.writeValueAsString(ownerBinding))
        || !freezeJson.equals(JSON.writeValueAsString(freeze))) {
      throw new ConflictException(
          "Frozen topology request was reused with changed complete input or checkpoint");
    }
    UUID captureId = required(row, "capture_id", UUID.class);
    if (!freeze.publicationFence().equals(required(row, "publication_fence", UUID.class))
        || !binding.requestId().equals(required(row, "request_id", UUID.class))
        || !binding.commitId().equals(required(row, "commit_id", UUID.class))
        || !ownerBinding
            .versionIdentityOperationId()
            .equals(required(row, "version_identity_operation_id", UUID.class))
        || !WorldCanonicalFrozenTopology.STATUS.equals(
            required(row, "capture_status", String.class))) {
      throw new ConflictException("Frozen topology journal identity or proof state differs");
    }
    String identityJson = required(row, "identity_json", String.class);
    String intakeJson = required(row, "intake_json", String.class);
    JSON.readTree(identityJson);
    JSON.readTree(intakeJson);
    var identity = topology.verifyOriginalSource(retained.plan(), identityJson, intakeJson);
    byte[] graphBytes = required(row, "graph_bytes", byte[].class);
    byte[] storageResult = required(row, "storage_result_bytes", byte[].class);
    if (!WorldAuthoredGraphSnapshotCapture.sha256(graphBytes)
        .equals(required(row, "graph_sha256", String.class))) {
      throw new ConflictException(
          "Frozen canonical graph bytes failed immutable integrity readback");
    }
    var graph = topology.verifyImmutableBytes(retained.plan(), graphBytes, storageResult);
    requirePrivateKeys(graph, identity);
    byte[] resultBytes = required(row, "result_bytes", byte[].class);
    if (!Arrays.equals(
        resultBytes,
        result(captureId, retained, identityJson, intakeJson, graphBytes, storageResult))) {
      throw new ConflictException(
          "Frozen topology result differs from every original retained field");
    }
    return new WorldCanonicalFrozenTopology(
        captureId, retained, identity, graph, graphBytes, storageResult, resultBytes);
  }

  private static void requirePrivateKeys(
      WorldCanonicalAuthoredGraph graph, WorldAuthoredVersionIdentityReceipt identity) {
    if (graph.localTenantKey() != identity.sourceIntakeReceipt().localTenantKey()
        || graph.localVersionKey() != identity.localVersionKey()) {
      throw new ConflictException(
          "Canonical graph private keys differ from original source identity");
    }
  }

  private byte[] result(
      UUID id,
      Request request,
      String identityJson,
      String intakeJson,
      byte[] graph,
      byte[] storageResult) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", "1");
    result.put("status", WorldCanonicalFrozenTopology.STATUS);
    result.put(
        "missingProof",
        java.util.List.of(
            "ACCOUNT_AUTHORIZATION",
            "COMPLETE_PARTICIPANT_COMMIT",
            "PUBLICATION_CHECKPOINT_DIGEST"));
    result.put("captureId", id.toString());
    result.put("freeze", request.freeze());
    result.put("ownerBinding", request.plan().ownerBinding());
    result.put("bindingJson", request.plan().binding().canonicalJson());
    result.put("bindingDigest", request.plan().binding().digest());
    result.put("identityJson", identityJson);
    result.put("intakeJson", intakeJson);
    result.put("graphBytes", graph);
    result.put("storageResultBytes", storageResult);
    return JSON.writeValueAsBytes(result);
  }

  private static <T> T required(Record row, String name, Class<T> type) {
    T value = row.get(name, type);
    if (value == null) throw new ConflictException("Frozen topology is missing " + name);
    return value;
  }
}
