package net.firedevops.firemud.common.entity;

import com.google.protobuf.ByteString;
import java.io.IOException;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogFraming;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogResponse;

/** Purpose-specific closed framing; never a generic registry or compatibility decoder. */
public final class EntityRuntimeFamilyCatalogCodec {
  public static final int MAX_REQUEST_BYTES = 1024;
  public static final int MAX_RESPONSE_BYTES = EntityRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 4096;
  private static final int REQUEST_V1 = 0x45435231;
  private static final int EVIDENCE_V1 = 0x45434531;

  private static final RuntimeFamilyCatalogFraming FRAMING =
      new RuntimeFamilyCatalogFraming(
          MAX_REQUEST_BYTES,
          MAX_RESPONSE_BYTES,
          EntityRuntimeFamilyCatalog.MAX_CATALOG_BYTES,
          "Frame bound exceeded",
          "Invalid field bound");

  private EntityRuntimeFamilyCatalogCodec() {}

  public static byte[] requestBytes(Request request) {
    return FRAMING.requestBytes(REQUEST_V1, request.readRequestId(), request.targetNamespace());
  }

  public static Request requestFromStored(byte[] bytes) {
    try {
      var frame =
          FRAMING.readRequest(
              bytes,
              REQUEST_V1,
              EntityRuntimeFamilyCatalog::requireUuid,
              "Noncanonical Entity catalog request");
      Request result = new Request(frame.requestId(), frame.namespace());
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, requestBytes(result), "Noncanonical Entity catalog request");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete Entity catalog request", invalid);
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
              EntityRuntimeFamilyCatalog::requireUuid,
              "Changed/noncanonical catalog evidence");
      Request request = requestFromStored(frame.request());
      var catalog = EntityRuntimeFamilyCatalog.fromStored(frame.catalog());
      Evidence result = new Evidence(request, frame.snapshotId(), catalog);
      if (!frame.digest().equals(catalog.digest())) {
        throw new IllegalArgumentException("Changed/noncanonical catalog evidence");
      }
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, evidenceBytes(result), "Changed/noncanonical catalog evidence");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete Entity catalog evidence", invalid);
    }
  }

  public static ReadEntityRuntimeFamilyCatalogRequest toRequest(Request request) {
    return ReadEntityRuntimeFamilyCatalogRequest.newBuilder()
        .addCanonicalRequestBytes(ByteString.copyFrom(requestBytes(request)))
        .build();
  }

  /** Workload authentication must precede decoding. */
  public static Request fromRequest(ReadEntityRuntimeFamilyCatalogRequest request) {
    if (request == null
        || request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getCanonicalRequestBytesCount() != 1)
      throw new IllegalArgumentException("One bounded closed catalog request required");
    return requestFromStored(request.getCanonicalRequestBytes(0).toByteArray());
  }

  public static ReadEntityRuntimeFamilyCatalogResponse toResponse(
      Request request, Evidence evidence) {
    if (!request.equals(evidence.request()))
      throw new IllegalArgumentException("Changed catalog read correlation");
    var response =
        ReadEntityRuntimeFamilyCatalogResponse.newBuilder()
            .addCanonicalResponseBytes(ByteString.copyFrom(evidenceBytes(evidence)))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES)
      throw new IllegalArgumentException("Oversized response");
    return response;
  }

  public static Evidence fromResponse(
      Request request, ReadEntityRuntimeFamilyCatalogResponse response) {
    if (response == null
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getCanonicalResponseBytesCount() != 1)
      throw new IllegalArgumentException("One complete bounded catalog response required");
    Evidence result = evidenceFromStored(response.getCanonicalResponseBytes(0).toByteArray());
    if (!request.equals(result.request()))
      throw new IllegalArgumentException("Changed catalog read correlation");
    return result;
  }
}
