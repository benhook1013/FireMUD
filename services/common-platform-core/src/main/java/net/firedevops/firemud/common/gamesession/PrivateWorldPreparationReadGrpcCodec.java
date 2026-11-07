package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.FrameReader;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Preparation;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Selection;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Source;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Target;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationRequest;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;
import tools.jackson.databind.json.JsonMapper;

/** Closed finite World-purpose transport, separate from Account's subset lifecycle read. */
public final class PrivateWorldPreparationReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 4096;
  public static final int MAX_RESPONSE_WIRE_BYTES = 65536;
  public static final int CALL_DEADLINE_SECONDS = 5;
  private static final String REQUEST = "game-session-private-world-preparation-request/v1";
  private static final String RESPONSE = "game-session-private-world-preparation-response/v2";

  private PrivateWorldPreparationReadGrpcCodec() {}

  public record Request(UUID readRequestId, Scope scope, UUID expectedPreparationRequestId) {
    public Request {
      PrivateWorldPreparationReadEvidence.uuid(readRequestId);
      if (readRequestId.version() != 4 || readRequestId.variant() != 2) throw invalid();
      Objects.requireNonNull(scope);
      PrivateWorldPreparationReadEvidence.uuid(expectedPreparationRequestId);
    }

    public byte[] canonicalBytes() {
      return PrivateWorldPreparationReadEvidence.framed(
          REQUEST,
          readRequestId.toString(),
          scope.targetNamespace(),
          scope.tenantId().toString(),
          scope.worldSlug(),
          scope.realmSlug(),
          expectedPreparationRequestId.toString());
    }
  }

  public static ReadPrivateWorldPreparationRequest toRequest(Request request) {
    var result =
        ReadPrivateWorldPreparationRequest.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(request.canonicalBytes()))
            .build();
    if (result.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) throw invalid();
    return result;
  }

  public static Request parseRequest(ReadPrivateWorldPreparationRequest wire) {
    if (wire == null
        || !wire.getUnknownFields().asMap().isEmpty()
        || wire.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) throw invalid();
    byte[] bytes = wire.getCanonicalEvidence().toByteArray();
    Request request = parseRequestBytes(bytes);
    if (!Arrays.equals(bytes, request.canonicalBytes())) throw invalid();
    return request;
  }

  private static Request parseRequestBytes(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_REQUEST_WIRE_BYTES) throw invalid();
    var reader = new FrameReader(bytes);
    reader.expect(REQUEST);
    var result =
        new Request(
            uuid(reader.text()),
            new Scope(reader.text(), uuid(reader.text()), reader.text(), reader.text()),
            uuid(reader.text()));
    reader.requireEnd();
    if (!Arrays.equals(bytes, result.canonicalBytes())) throw invalid();
    return result;
  }

  public static ReadPrivateWorldPreparationResponse toResponse(Request request, Snapshot snapshot) {
    var evidence = new Evidence(request, snapshot);
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, RESPONSE);
    DraftAuthorizationFenceBinding.frame(out, evidence.request().canonicalBytes());
    writeSnapshot(out, snapshot);
    DraftAuthorizationFenceBinding.frame(out, snapshot.observedAt().toString());
    var result =
        ReadPrivateWorldPreparationResponse.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(out.toByteArray()))
            .build();
    if (result.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) throw invalid();
    return result;
  }

  public static Evidence fromResponse(Request expected, ReadPrivateWorldPreparationResponse wire) {
    Objects.requireNonNull(expected);
    if (wire == null
        || !wire.getUnknownFields().asMap().isEmpty()
        || wire.getCanonicalEvidence().isEmpty()
        || wire.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) throw invalid();
    byte[] bytes = wire.getCanonicalEvidence().toByteArray();
    var reader = new FrameReader(bytes);
    reader.expect(RESPONSE);
    Request echoed = parseRequestBytes(reader.bytes());
    if (!expected.equals(echoed)) throw invalid();
    var selectionRequest = uuid(reader.text());
    String presence = reader.text();
    Long expectedRevision = null;
    UUID expectedLifecycle = null;
    if ("PRESENT".equals(presence)) {
      expectedRevision = positive(reader.text());
      expectedLifecycle = uuid(reader.text());
    } else if (!"ABSENT".equals(presence)) throw invalid();
    var selection =
        new Selection(
            selectionRequest,
            expectedRevision,
            expectedLifecycle,
            positive(reader.text()),
            reader.text(),
            instant(reader.text()),
            ByteString.copyFrom(reader.bytes()));
    var preparation =
        new Preparation(
            uuid(reader.text()),
            uuid(reader.text()),
            uuid(reader.text()),
            positive(reader.text()),
            reader.text(),
            reader.text(),
            reader.text(),
            instant(reader.text()),
            instant(reader.text()),
            ByteString.copyFrom(reader.bytes()));
    UUID catalogRealm = uuid(reader.text());
    UUID catalogRequest = uuid(reader.text());
    long catalogRevision = positive(reader.text());
    String catalogReceipt = reader.text();
    UUID intake = uuid(reader.text());
    String intakeReceipt = reader.text();
    UUID sourceOperation = uuid(reader.text());
    String sourceDigest = reader.text();
    UUID version = uuid(reader.text());
    String bundle = reader.text();
    String bundleDigest = reader.text();
    String policySet = reader.text();
    String policyCarrier = reader.text();
    UUID policyId = uuid(reader.text());
    String policyDigest = reader.text();
    UUID commit = uuid(reader.text());
    UUID revision = uuid(reader.text());
    String logical = reader.text();
    var policy = RealmEntryPolicy.parseCanonical(reader.text(), JsonMapper.builder().build());
    var source =
        new Source(
            catalogRealm,
            catalogRequest,
            catalogRevision,
            catalogReceipt,
            intake,
            intakeReceipt,
            sourceOperation,
            sourceDigest,
            version,
            bundle,
            bundleDigest,
            policySet,
            policyCarrier,
            new RealmPolicyPublishedEvidence.Policy(
                policyId,
                new RealmPolicySource.Policy(commit, revision, logical, policy),
                policyDigest));
    var target =
        new Target(
            echoed.scope(),
            uuid(reader.text()),
            reader.text(),
            bool(reader.text()),
            bool(reader.text()),
            reader.text(),
            uuid(reader.text()),
            reader.text(),
            positive(reader.text()),
            uuid(reader.text()),
            reader.text(),
            reader.text(),
            uuid(reader.text()),
            uuid(reader.text()),
            positive(reader.text()),
            reader.text(),
            uuid(reader.text()),
            uuid(reader.text()),
            reader.text(),
            uuid(reader.text()),
            reader.text(),
            reader.text());
    var snapshot =
        new Snapshot(
            echoed.scope(),
            selection,
            preparation,
            source,
            target,
            new PrivateWorldPreparationReadEvidence.RuntimeAllocation(
                uuid(reader.text()), uuid(reader.text()), reader.text(), reader.text()),
            instant(reader.text()));
    reader.requireEnd();
    if (!Arrays.equals(bytes, toResponse(echoed, snapshot).getCanonicalEvidence().toByteArray()))
      throw invalid();
    return new Evidence(echoed, snapshot);
  }

  /** Complete stable source content; only the independent observation time/read ID are excluded. */
  public static byte[] stableBytes(Snapshot snapshot) {
    var out = new ByteArrayOutputStream();
    fields(
        out,
        snapshot.scope().targetNamespace(),
        snapshot.scope().tenantId().toString(),
        snapshot.scope().worldSlug(),
        snapshot.scope().realmSlug());
    writeSnapshot(out, snapshot);
    return out.toByteArray();
  }

  private static void writeSnapshot(ByteArrayOutputStream out, Snapshot snapshot) {
    var s = snapshot.selection();
    fields(out, s.requestId().toString(), s.expectedHeadRevision() == null ? "ABSENT" : "PRESENT");
    if (s.expectedHeadRevision() != null)
      fields(out, s.expectedHeadRevision().toString(), s.expectedCurrentLifecycleId().toString());
    fields(out, Long.toString(s.headRevision()), s.requestDigest(), s.selectedAt().toString());
    DraftAuthorizationFenceBinding.frame(out, s.originalRequest().toByteArray());
    var p = snapshot.preparation();
    fields(
        out,
        p.requestId().toString(),
        p.lifecycleId().toString(),
        p.namespaceId().toString(),
        Long.toString(p.generation()),
        p.state(),
        p.initializationMode(),
        p.requestDigest(),
        p.createdAt().toString(),
        p.forkExpiresAt().toString());
    DraftAuthorizationFenceBinding.frame(out, p.originalRequest().toByteArray());
    var source = snapshot.source();
    var policy = source.targetPolicy();
    fields(
        out,
        source.catalogRealmId().toString(),
        source.catalogRequestId().toString(),
        Long.toString(source.catalogRevision()),
        source.catalogReceiptDigest(),
        source.intakeOperationId().toString(),
        source.intakeReceiptDigest(),
        source.sourceOperationId().toString(),
        source.sourceEvidenceDigest(),
        source.canonicalVersionId().toString(),
        source.publishedReleaseBundleRef(),
        source.publishedReleaseBundleDigest(),
        source.policySetDigest(),
        source.policyCarrierDigest(),
        policy.policyId().toString(),
        policy.policyDigest(),
        policy.source().commitId().toString(),
        policy.source().revisionId().toString(),
        policy.source().logicalRevisionId(),
        policy.source().policy().canonicalJson());
    var target = snapshot.target();
    fields(
        out,
        target.realmId().toString(),
        target.realmDisplayName(),
        Boolean.toString(target.visible()),
        Boolean.toString(target.publicProduction()),
        target.stateScope(),
        target.namespaceId().toString(),
        target.characterCreationPolicy(),
        Long.toString(target.catalogRevision()),
        target.creationRequestId().toString(),
        target.requestDigest(),
        target.receiptDigest(),
        target.preparationRequestId().toString(),
        target.lifecycleId().toString(),
        Long.toString(target.generation()),
        target.state(),
        target.sourceCatalogRealmId().toString(),
        target.targetPolicyId().toString(),
        target.targetPolicyDigest(),
        target.canonicalVersionId().toString(),
        target.publishedReleaseBundleRef(),
        target.publishedReleaseBundleDigest());
    var allocation = snapshot.runtimeAllocation();
    fields(
        out,
        allocation.requestId().toString(),
        allocation.gameInstanceId().toString(),
        allocation.requestDigest(),
        allocation.receiptDigest());
  }

  private static void fields(ByteArrayOutputStream out, String... values) {
    for (String value : values) DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static UUID uuid(String text) {
    UUID value = UUID.fromString(text);
    PrivateWorldPreparationReadEvidence.uuid(value);
    if (!value.toString().equals(text)) throw invalid();
    return value;
  }

  private static long positive(String text) {
    if (!text.matches("[1-9][0-9]{0,18}")) throw invalid();
    return Long.parseLong(text);
  }

  private static Instant instant(String text) {
    final Instant value;
    try {
      value = Instant.parse(text);
    } catch (java.time.DateTimeException malformed) {
      throw invalid();
    }
    if (!value.toString().equals(text)) throw invalid();
    return value;
  }

  private static boolean bool(String text) {
    if (!"true".equals(text) && !"false".equals(text)) throw invalid();
    return Boolean.parseBoolean(text);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Closed bounded private World preparation frame required");
  }
}
