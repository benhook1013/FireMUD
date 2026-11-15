package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchResponse;

/**
 * Closed exact request and owner readback for an original Account StartSession launch.
 *
 * <p>This carrier validates bytes and tuple-to-launch correlation only. It does not authenticate
 * either owner, prove currentness, require a lifecycle state, or grant lifecycle/admission
 * authority.
 */
public final class OriginalStartSessionLaunchReadEvidence {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_REQUEST_BYTES =
      StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES + 8 * 1_024;
  public static final int MAX_RESULT_BYTES = 41 * 1024 * 1024;

  private static final UUID NORMALIZED_READ_REQUEST_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private OriginalStartSessionLaunchReadEvidence() {}

  /** Exact selector and complete original tuple presented to the Account-only Game Session API. */
  public static final class Request {
    private final CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector;
    private final byte[] originalPostAuthorizationTupleBytes;
    private final byte[] canonicalBytes;

    public Request(
        CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector,
        byte[] originalPostAuthorizationTupleBytes) {
      this.selector = Objects.requireNonNull(selector, "selector");
      this.originalPostAuthorizationTupleBytes =
          boundedTuple(originalPostAuthorizationTupleBytes, "original post-authorization tuple");
      requireTupleSelectorCorrelation(this.selector, this.originalPostAuthorizationTupleBytes);
      this.canonicalBytes = encodeRequest(this.selector, this.originalPostAuthorizationTupleBytes);
    }

    /** Strictly decodes one complete bounded canonical request; decoding grants no authority. */
    public static Request decode(byte[] exactCanonicalBytes) {
      byte[] stored = boundedRequest(exactCanonicalBytes);
      try {
        ReadOriginalStartSessionLaunchRequest wire =
            ReadOriginalStartSessionLaunchRequest.parseFrom(stored);
        requireNoUnknownFields(wire, "original StartSession launch read request");
        if (wire.getSchemaVersion() != SCHEMA_VERSION || !wire.hasSelector()) {
          throw invalid("Original StartSession launch read request schema or selector is invalid");
        }
        var selector =
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromRequest(wire.getSelector());
        Request result =
            new Request(selector, wire.getOriginalPostAuthorizationTupleBytes().toByteArray());
        if (!Arrays.equals(stored, result.canonicalBytes)) {
          throw invalid("Original StartSession launch read request is not canonical");
        }
        return result;
      } catch (IllegalArgumentException expected) {
        throw expected;
      } catch (Exception malformed) {
        throw invalid("Original StartSession launch read request is malformed");
      }
    }

    public CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector() {
      return selector;
    }

    public byte[] originalPostAuthorizationTupleBytes() {
      return originalPostAuthorizationTupleBytes.clone();
    }

    /** Full canonical bytes retain the transient owner read identifier. */
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    /** Stable operation bytes normalize only the selector's transient read identifier. */
    public byte[] operationIdentityBytes() {
      return encodeRequest(
          withReadRequestId(selector, normalizedReadRequestId(selector)),
          originalPostAuthorizationTupleBytes);
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || other instanceof Request that && Arrays.equals(canonicalBytes, that.canonicalBytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(canonicalBytes);
    }
  }

  /** Exact Game Session owner result and the tuple bytes actually retained in its row. */
  public static final class Result {
    private final Request request;
    private final CanonicalGameInstanceLaunchAssociationReadEvidence.Result launch;
    private final byte[] retainedOriginalPostAuthorizationTupleBytes;
    private final byte[] canonicalBytes;

    public Result(
        Request request,
        CanonicalGameInstanceLaunchAssociationReadEvidence.Result launch,
        byte[] retainedOriginalPostAuthorizationTupleBytes) {
      this.request = Objects.requireNonNull(request, "request");
      this.launch = Objects.requireNonNull(launch, "launch");
      this.retainedOriginalPostAuthorizationTupleBytes =
          boundedTuple(
              retainedOriginalPostAuthorizationTupleBytes,
              "retained original post-authorization tuple");
      if (!request.selector().equals(launch.request())
          || !Arrays.equals(
              request.originalPostAuthorizationTupleBytes(),
              this.retainedOriginalPostAuthorizationTupleBytes)) {
        throw invalid("Game Session launch read does not echo the exact selector and tuple");
      }
      requireTupleLaunchCorrelation(request.originalPostAuthorizationTupleBytes(), launch);
      this.canonicalBytes = encodeResult(this, false);
    }

    /** Strictly decodes one complete bounded result including the full launch readback. */
    public static Result decode(byte[] exactCanonicalBytes) {
      byte[] stored = boundedResult(exactCanonicalBytes);
      try {
        ReadOriginalStartSessionLaunchResponse wire =
            ReadOriginalStartSessionLaunchResponse.parseFrom(stored);
        requireNoUnknownFields(wire, "original StartSession launch read response");
        if (wire.getSchemaVersion() != SCHEMA_VERSION || !wire.hasRequest() || !wire.hasLaunch()) {
          throw invalid("Original StartSession launch read response schema or evidence is invalid");
        }
        Request request = Request.decode(wire.getRequest().toByteArray());
        var launch =
            CanonicalGameInstanceLaunchAssociationReadGrpcCodec.fromResponse(
                request.selector(), wire.getLaunch());
        Result result =
            new Result(
                request,
                launch,
                wire.getRetainedOriginalPostAuthorizationTupleBytes().toByteArray());
        if (!Arrays.equals(stored, result.canonicalBytes)) {
          throw invalid("Original StartSession launch read result is not canonical");
        }
        return result;
      } catch (IllegalArgumentException expected) {
        throw expected;
      } catch (Exception malformed) {
        throw invalid("Original StartSession launch read result is malformed");
      }
    }

    public Request request() {
      return request;
    }

    public CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector() {
      return request.selector();
    }

    public CanonicalGameInstanceLaunchAssociationReadEvidence.Result launch() {
      return launch;
    }

    public byte[] retainedOriginalPostAuthorizationTupleBytes() {
      return retainedOriginalPostAuthorizationTupleBytes.clone();
    }

    /** Full canonical result retains both selector and owner-response read identifiers. */
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    /** Stable operation bytes normalize only the selector's transient read identifier. */
    public byte[] operationIdentityBytes() {
      return encodeResult(this, true);
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || other instanceof Result that && Arrays.equals(canonicalBytes, that.canonicalBytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(canonicalBytes);
    }
  }

  private static byte[] encodeRequest(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector, byte[] tupleBytes) {
    var message =
        ReadOriginalStartSessionLaunchRequest.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setSelector(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(selector))
            .setOriginalPostAuthorizationTupleBytes(
                com.google.protobuf.ByteString.copyFrom(tupleBytes))
            .build();
    if (message.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw invalid("Original StartSession launch read request exceeds its byte limit");
    }
    return message.toByteArray();
  }

  private static byte[] encodeResult(Result result, boolean normalizeReadRequestId) {
    var selector = result.request().selector();
    if (normalizeReadRequestId) {
      selector = withReadRequestId(selector, normalizedReadRequestId(selector));
    }
    var requestMessage =
        ReadOriginalStartSessionLaunchRequest.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setSelector(CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toRequest(selector))
            .setOriginalPostAuthorizationTupleBytes(
                com.google.protobuf.ByteString.copyFrom(
                    result.request().originalPostAuthorizationTupleBytes()))
            .build();
    var launchMessage =
        CanonicalGameInstanceLaunchAssociationReadGrpcCodec.toResponse(result.launch());
    if (normalizeReadRequestId) {
      launchMessage =
          launchMessage.toBuilder().setReadRequestId(selector.readRequestId().toString()).build();
    }
    var message =
        ReadOriginalStartSessionLaunchResponse.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setRequest(requestMessage)
            .setLaunch(launchMessage)
            .setRetainedOriginalPostAuthorizationTupleBytes(
                com.google.protobuf.ByteString.copyFrom(
                    result.retainedOriginalPostAuthorizationTupleBytes()))
            .build();
    if (message.getSerializedSize() > MAX_RESULT_BYTES) {
      throw invalid("Original StartSession launch read result exceeds its byte limit");
    }
    return message.toByteArray();
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request withReadRequestId(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector, UUID readRequestId) {
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        readRequestId,
        selector.targetNamespace(),
        selector.canonicalTenantId(),
        selector.worldSlug(),
        selector.gameInstanceUuid(),
        selector.controlPlaneRequestId(),
        selector.launchDescriptorId(),
        selector.expectedDescriptorRequestDigest(),
        selector.expectedDescriptorResultDigest(),
        selector.expectedReleaseAttestationEvidenceDigest());
  }

  private static UUID normalizedReadRequestId(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector) {
    long suffix = 1L;
    UUID normalized = NORMALIZED_READ_REQUEST_ID;
    while (normalized.toString().equals(selector.controlPlaneRequestId())) {
      normalized = new UUID(0L, ++suffix);
    }
    return normalized;
  }

  private static void requireTupleSelectorCorrelation(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector, byte[] tupleBytes) {
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    var preAuthorization = tuple.preAuthorizationTuple();
    var action = preAuthorization.action();
    if (!StartSessionOperatorAction.ACTION_FAMILY.equals(preAuthorization.actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(preAuthorization.targetOwner())
        || !tuple.controlPlaneRequestId().equals(selector.controlPlaneRequestId())
        || !action.scope().targetNamespace().equals(selector.targetNamespace())
        || !action.scope().tenantId().equals(selector.canonicalTenantId())) {
      throw invalid("Original StartSession tuple differs from the exact launch selector");
    }
  }

  private static void requireTupleLaunchCorrelation(
      byte[] tupleBytes, CanonicalGameInstanceLaunchAssociationReadEvidence.Result launch) {
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(tupleBytes);
    AuthoredWorldLaunchDescriptorEvidence descriptor = launch.launchBindingEvidence().descriptor();
    var preAuthorization = tuple.preAuthorizationTuple();
    var action = preAuthorization.action();
    var selector = launch.request();
    if (!StartSessionOperatorAction.ACTION_FAMILY.equals(preAuthorization.actionFamily())
        || !StartSessionOperatorAction.OWNER_SERVICE.equals(preAuthorization.targetOwner())
        || !tuple.controlPlaneRequestId().equals(selector.controlPlaneRequestId())
        || !action.scope().targetNamespace().equals(selector.targetNamespace())
        || !action.scope().tenantId().equals(selector.canonicalTenantId())
        || action.target().gameTemplateId() != descriptor.gameTemplateId()
        || !selector.targetNamespace().equals(descriptor.targetNamespace())
        || !selector.canonicalTenantId().equals(descriptor.canonicalTenantId())
        || !selector.worldSlug().equals(descriptor.worldSlug())
        || !selector.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())) {
      throw invalid("Original StartSession tuple and complete launch binding differ");
    }
  }

  private static byte[] boundedTuple(byte[] bytes, String label) {
    if (bytes == null
        || bytes.length == 0
        || bytes.length > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw invalid(label + " is empty or exceeds its byte limit");
    }
    byte[] copy = bytes.clone();
    StartSessionPostAuthorizationExecutionTuple decoded =
        StartSessionPostAuthorizationExecutionTuple.decode(copy);
    if (!Arrays.equals(copy, decoded.canonicalBytes())) {
      throw invalid(label + " is not its exact canonical byte representation");
    }
    return copy;
  }

  private static byte[] boundedRequest(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES) {
      throw invalid("Complete bounded original StartSession launch read request required");
    }
    return bytes.clone();
  }

  private static byte[] boundedResult(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESULT_BYTES) {
      throw invalid("Complete bounded original StartSession launch read result required");
    }
    return bytes.clone();
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw invalid(label + " contains unsupported fields");
    }
    for (var field : message.getAllFields().entrySet()) {
      Descriptors.FieldDescriptor descriptor = field.getKey();
      if (descriptor.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
        continue;
      }
      if (descriptor.isRepeated()) {
        for (Object nested : (List<?>) field.getValue()) {
          requireNoUnknownFields((Message) nested, label + "." + descriptor.getName());
        }
      } else {
        requireNoUnknownFields((Message) field.getValue(), label + "." + descriptor.getName());
      }
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
