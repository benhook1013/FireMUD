package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import java.io.IOException;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogFraming;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogResponse;

/** One closed finite binary frame per method; no generic parser or compatibility interpretation. */
public final class WorldRuntimeFamilyCatalogCodec {
  public static final int MAX_REQUEST_BYTES = 1024;
  public static final int MAX_RESPONSE_BYTES = WorldRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 4096;
  private static final int REQUEST_V1 = 0x57435231;
  private static final int EVIDENCE_V1 = 0x57434531;

  private static final RuntimeFamilyCatalogFraming FRAMING =
      new RuntimeFamilyCatalogFraming(
          MAX_REQUEST_BYTES,
          MAX_RESPONSE_BYTES,
          WorldRuntimeFamilyCatalog.MAX_CATALOG_BYTES,
          "Frame bound exceeded",
          "Invalid field bound");

  private WorldRuntimeFamilyCatalogCodec() {}

  public static byte[] requestBytes(Request request) {
    return FRAMING.requestBytes(REQUEST_V1, request.readRequestId(), request.targetNamespace());
  }

  public static Request requestFromStored(byte[] bytes) {
    try {
      var frame =
          FRAMING.readRequest(
              bytes,
              REQUEST_V1,
              WorldRuntimeFamilyCatalog::requireUuid,
              "Noncanonical World catalog request");
      Request result = new Request(frame.requestId(), frame.namespace());
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, requestBytes(result), "Noncanonical World catalog request");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete World catalog request", invalid);
    }
  }

  public static byte[] evidenceBytes(Evidence evidence) {
    return FRAMING.evidenceBytes(
        EVIDENCE_V1,
        requestBytes(evidence.request()),
        evidence.snapshotId(),
        evidence.catalog().digest(),
        evidence.catalog().exactBytes(),
        "Oversized complete catalog evidence");
  }

  public static Evidence evidenceFromStored(byte[] bytes) {
    try {
      var frame =
          FRAMING.readEvidence(
              bytes,
              EVIDENCE_V1,
              WorldRuntimeFamilyCatalog::requireUuid,
              "Changed/noncanonical catalog evidence");
      Request request = requestFromStored(frame.request());
      var catalog = WorldRuntimeFamilyCatalog.fromStored(frame.catalog());
      Evidence result = new Evidence(request, frame.snapshotId(), catalog);
      if (!frame.digest().equals(catalog.digest())) {
        throw new IllegalArgumentException("Changed/noncanonical catalog evidence");
      }
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, evidenceBytes(result), "Changed/noncanonical catalog evidence");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete World catalog evidence", invalid);
    }
  }

  public static ReadWorldRuntimeFamilyCatalogRequest toRequest(Request request) {
    return ReadWorldRuntimeFamilyCatalogRequest.newBuilder()
        .addCanonicalRequestBytes(ByteString.copyFrom(requestBytes(request)))
        .build();
  }

  /** Receiver must authenticate its workload before invoking this decoder. */
  public static Request fromRequest(ReadWorldRuntimeFamilyCatalogRequest request) {
    if (request == null
        || request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getCanonicalRequestBytesCount() != 1) {
      throw new IllegalArgumentException("One bounded closed catalog request required");
    }
    return requestFromStored(request.getCanonicalRequestBytes(0).toByteArray());
  }

  public static ReadWorldRuntimeFamilyCatalogResponse toResponse(
      Request request, Evidence evidence) {
    if (!request.equals(evidence.request()))
      throw new IllegalArgumentException("Changed catalog read correlation");
    var response =
        ReadWorldRuntimeFamilyCatalogResponse.newBuilder()
            .addCanonicalResponseBytes(ByteString.copyFrom(evidenceBytes(evidence)))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES)
      throw new IllegalArgumentException("Oversized response");
    return response;
  }

  public static Evidence fromResponse(
      Request request, ReadWorldRuntimeFamilyCatalogResponse response) {
    if (response == null
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getCanonicalResponseBytesCount() != 1) {
      throw new IllegalArgumentException("One complete bounded catalog response required");
    }
    Evidence result = evidenceFromStored(response.getCanonicalResponseBytes(0).toByteArray());
    if (!request.equals(result.request()))
      throw new IllegalArgumentException("Changed catalog read correlation");
    return result;
  }
}
