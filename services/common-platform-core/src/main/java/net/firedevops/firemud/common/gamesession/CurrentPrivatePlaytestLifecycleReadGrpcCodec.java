package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Evidence;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.ReadState;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Snapshot;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleRequest;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePlaytestLifecycleResponse;

/** Closed, bounded Account-to-Game-Session codec for current private-playtest source facts. */
public final class CurrentPrivatePlaytestLifecycleReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 4096;
  public static final int MAX_RESPONSE_WIRE_BYTES = 8192;
  private static final String REQUEST_SCHEMA =
      "game-session-current-private-playtest-read-request/v1";
  private static final String RESPONSE_SCHEMA =
      "game-session-current-private-playtest-read-response/v1";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private CurrentPrivatePlaytestLifecycleReadGrpcCodec() {}

  /** Correlates a read and selects one exact canonical target; it contains no Account principal. */
  public record Request(UUID readRequestId, Scope scope) {
    public Request {
      requireUuid(readRequestId, "readRequestId");
      Objects.requireNonNull(scope, "scope");
    }

    public byte[] canonicalBytes() {
      var output = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(output, REQUEST_SCHEMA);
      DraftAuthorizationFenceBinding.frame(output, readRequestId.toString());
      DraftAuthorizationFenceBinding.frame(output, scope.targetNamespace());
      DraftAuthorizationFenceBinding.frame(output, scope.tenantId().toString());
      DraftAuthorizationFenceBinding.frame(output, scope.worldSlug());
      DraftAuthorizationFenceBinding.frame(output, scope.realmSlug());
      return output.toByteArray();
    }
  }

  public static ReadCurrentPrivatePlaytestLifecycleRequest toRequest(Request request) {
    Objects.requireNonNull(request, "request");
    byte[] canonical = request.canonicalBytes();
    var wire =
        ReadCurrentPrivatePlaytestLifecycleRequest.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(canonical))
            .build();
    if (wire.getSerializedSize() > MAX_REQUEST_WIRE_BYTES) {
      throw new IllegalArgumentException("Current private-playtest request exceeds its byte bound");
    }
    return wire;
  }

  /** Decodes a closed, exact target selector; peer authentication belongs to the receiver. */
  public static Request parseRequest(ReadCurrentPrivatePlaytestLifecycleRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES
        || request.getCanonicalEvidence().isEmpty()
        || !request.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "Closed bounded current private-playtest request required");
    }
    byte[] canonical = request.getCanonicalEvidence().toByteArray();
    Request decoded = parseRequestBytes(canonical);
    if (!Arrays.equals(canonical, decoded.canonicalBytes())) {
      throw new IllegalArgumentException("Current private-playtest request frame is not canonical");
    }
    return decoded;
  }

  /** Encodes a correlated response, including an explicit absent-current result. */
  public static ReadCurrentPrivatePlaytestLifecycleResponse toResponse(
      Request request, Optional<Snapshot> current) {
    Evidence evidence = new Evidence(request, current);
    var output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, RESPONSE_SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, evidence.request().canonicalBytes());
    if (evidence.current().isEmpty()) {
      DraftAuthorizationFenceBinding.frame(output, "ABSENT");
    } else {
      DraftAuthorizationFenceBinding.frame(output, "PRESENT");
      writeSnapshot(output, evidence.current().orElseThrow());
    }
    var response =
        ReadCurrentPrivatePlaytestLifecycleResponse.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(output.toByteArray()))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) {
      throw new IllegalArgumentException(
          "Current private-playtest response exceeds its byte bound");
    }
    return response;
  }

  /** Returns typed evidence only after validating the closed frame and exact request echo. */
  public static Evidence fromResponse(
      Request expected, ReadCurrentPrivatePlaytestLifecycleResponse response) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(response, "response");
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || response.getCanonicalEvidence().isEmpty()
        || !response.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(
          "Closed bounded current private-playtest response required");
    }
    byte[] canonical = response.getCanonicalEvidence().toByteArray();
    var reader = new DraftAuthorizationFenceBinding.FrameReader(canonical);
    reader.expect(RESPONSE_SCHEMA);
    Request echoed = parseRequestBytes(reader.bytes());
    if (!expected.equals(echoed)) {
      throw new IllegalArgumentException(
          "Current private-playtest response changed the read request");
    }
    String presence = reader.text();
    Optional<Snapshot> current;
    if ("ABSENT".equals(presence)) {
      current = Optional.empty();
    } else if ("PRESENT".equals(presence)) {
      current = Optional.of(readSnapshot(reader, echoed.scope()));
    } else {
      throw new IllegalArgumentException("Current private-playtest response presence is invalid");
    }
    reader.requireEnd();
    Evidence evidence = new Evidence(echoed, current);
    ReadCurrentPrivatePlaytestLifecycleResponse reencoded =
        toResponse(evidence.request(), evidence.current());
    if (!Arrays.equals(canonical, reencoded.getCanonicalEvidence().toByteArray())) {
      throw new IllegalArgumentException(
          "Current private-playtest response frame is not canonical");
    }
    return evidence;
  }

  private static Request parseRequestBytes(byte[] canonical) {
    if (canonical == null || canonical.length == 0 || canonical.length > MAX_REQUEST_WIRE_BYTES) {
      throw new IllegalArgumentException(
          "Bounded canonical current private-playtest request frame required");
    }
    var reader = new DraftAuthorizationFenceBinding.FrameReader(canonical);
    reader.expect(REQUEST_SCHEMA);
    Request request =
        new Request(
            canonicalUuid(reader.text(), "readRequestId"),
            new Scope(
                reader.text(),
                canonicalUuid(reader.text(), "tenantId"),
                reader.text(),
                reader.text()));
    reader.requireEnd();
    return request;
  }

  private static void writeSnapshot(ByteArrayOutputStream output, Snapshot snapshot) {
    DraftAuthorizationFenceBinding.frame(output, snapshot.selectionRequestId().toString());
    DraftAuthorizationFenceBinding.frame(output, Long.toString(snapshot.selectionHeadRevision()));
    DraftAuthorizationFenceBinding.frame(output, snapshot.selectionRequestDigest());
    DraftAuthorizationFenceBinding.frame(output, snapshot.selectedAt().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.preparationRequestId().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.preparationRequestDigest());
    DraftAuthorizationFenceBinding.frame(output, snapshot.playtestLifecycleId().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.playableStateNamespaceId().toString());
    DraftAuthorizationFenceBinding.frame(output, Long.toString(snapshot.playtestStateGeneration()));
    DraftAuthorizationFenceBinding.frame(output, snapshot.forkExpiresAt().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.preparationState());
    DraftAuthorizationFenceBinding.frame(output, snapshot.readState().name());
    DraftAuthorizationFenceBinding.frame(output, snapshot.observedAt().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.canonicalVersionId().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.sourceCatalogRealmId().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.sourceCatalogRequestId().toString());
    DraftAuthorizationFenceBinding.frame(output, Long.toString(snapshot.sourceCatalogRevision()));
    DraftAuthorizationFenceBinding.frame(output, snapshot.publishedReleaseBundleRef());
    DraftAuthorizationFenceBinding.frame(output, snapshot.publishedReleaseBundleDigest());
    DraftAuthorizationFenceBinding.frame(output, snapshot.privatePolicyId().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.privatePolicyDigest());
    DraftAuthorizationFenceBinding.frame(output, snapshot.policySourceCommitId().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.policySourceRevisionId().toString());
    DraftAuthorizationFenceBinding.frame(output, snapshot.policyLogicalRevisionId());
    DraftAuthorizationFenceBinding.frame(output, snapshot.policySetDigest());
  }

  private static Snapshot readSnapshot(
      DraftAuthorizationFenceBinding.FrameReader reader, Scope scope) {
    UUID selectionRequestId = canonicalUuid(reader.text(), "selectionRequestId");
    long selectionHeadRevision = canonicalPositiveLong(reader.text(), "selectionHeadRevision");
    String selectionRequestDigest = reader.text();
    Instant selectedAt = canonicalInstant(reader.text(), "selectedAt");
    UUID preparationRequestId = canonicalUuid(reader.text(), "preparationRequestId");
    String preparationRequestDigest = reader.text();
    UUID playtestLifecycleId = canonicalUuid(reader.text(), "playtestLifecycleId");
    UUID playableStateNamespaceId = canonicalUuid(reader.text(), "playableStateNamespaceId");
    long playtestStateGeneration = canonicalPositiveLong(reader.text(), "playtestStateGeneration");
    Instant forkExpiresAt = canonicalInstant(reader.text(), "forkExpiresAt");
    String preparationState = reader.text();
    ReadState readState;
    try {
      readState = ReadState.valueOf(reader.text());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Current private-playtest read state is invalid", invalid);
    }
    Instant observedAt = canonicalInstant(reader.text(), "observedAt");
    UUID canonicalVersionId = canonicalUuid(reader.text(), "canonicalVersionId");
    UUID sourceCatalogRealmId = canonicalUuid(reader.text(), "sourceCatalogRealmId");
    UUID sourceCatalogRequestId = canonicalUuid(reader.text(), "sourceCatalogRequestId");
    long sourceCatalogRevision = canonicalPositiveLong(reader.text(), "sourceCatalogRevision");
    String publishedReleaseBundleRef = reader.text();
    String publishedReleaseBundleDigest = reader.text();
    UUID privatePolicyId = canonicalUuid(reader.text(), "privatePolicyId");
    String privatePolicyDigest = reader.text();
    UUID policySourceCommitId = canonicalUuid(reader.text(), "policySourceCommitId");
    UUID policySourceRevisionId = canonicalUuid(reader.text(), "policySourceRevisionId");
    String policyLogicalRevisionId = reader.text();
    String policySetDigest = reader.text();
    return new Snapshot(
        scope,
        selectionRequestId,
        selectionHeadRevision,
        selectionRequestDigest,
        selectedAt,
        preparationRequestId,
        preparationRequestDigest,
        playtestLifecycleId,
        playableStateNamespaceId,
        playtestStateGeneration,
        forkExpiresAt,
        preparationState,
        readState,
        observedAt,
        canonicalVersionId,
        sourceCatalogRealmId,
        sourceCatalogRequestId,
        sourceCatalogRevision,
        publishedReleaseBundleRef,
        publishedReleaseBundleDigest,
        privatePolicyId,
        privatePolicyDigest,
        policySourceCommitId,
        policySourceRevisionId,
        policyLogicalRevisionId,
        policySetDigest);
  }

  private static UUID canonicalUuid(String value, String name) {
    try {
      UUID parsed = UUID.fromString(Objects.requireNonNull(value, name));
      if (!parsed.toString().equals(value) || NIL_UUID.equals(parsed)) {
        throw new IllegalArgumentException(name + " must be canonical lowercase non-nil UUID text");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          name + " must be canonical lowercase non-nil UUID text", invalid);
    }
  }

  private static long canonicalPositiveLong(String value, String name) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
      throw new IllegalArgumentException(name + " must be a canonical positive decimal");
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(name + " must be a canonical positive decimal");
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(name + " must be a canonical positive decimal", invalid);
    }
  }

  private static Instant canonicalInstant(String value, String name) {
    try {
      Instant parsed = Instant.parse(Objects.requireNonNull(value, name));
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException(name + " must use canonical UTC instant text");
      }
      return parsed;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(name + " must use canonical UTC instant text", invalid);
    }
  }

  private static void requireUuid(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }
}
