package net.firedevops.firemud.common.social;

import com.google.protobuf.ByteString;
import java.io.IOException;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogFraming;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogResponse;

/** One closed finite binary frame per method; no generic parser or compatibility interpretation. */
public final class SocialRuntimeFamilyCatalogCodec {
  public static final int MAX_REQUEST_BYTES = 1024;
  public static final int MAX_RESPONSE_BYTES = SocialRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 4096;
  private static final int REQUEST_V1 = 0x53435231;
  private static final int EVIDENCE_V1 = 0x53434531;

  private static final RuntimeFamilyCatalogFraming FRAMING =
      new RuntimeFamilyCatalogFraming(
          MAX_REQUEST_BYTES,
          MAX_RESPONSE_BYTES,
          SocialRuntimeFamilyCatalog.MAX_CATALOG_BYTES,
          "Social catalog frame bound exceeded",
          "Invalid Social catalog field bound");

  private SocialRuntimeFamilyCatalogCodec() {}

  public static byte[] requestBytes(Request request) {
    return FRAMING.requestBytes(REQUEST_V1, request.readRequestId(), request.targetNamespace());
  }

  public static Request requestFromStored(byte[] bytes) {
    try {
      var frame =
          FRAMING.readRequest(
              bytes,
              REQUEST_V1,
              SocialRuntimeFamilyCatalog::requireUuid,
              "Noncanonical Social catalog request");
      Request result = new Request(frame.requestId(), frame.namespace());
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, requestBytes(result), "Noncanonical Social catalog request");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete Social catalog request", invalid);
    }
  }

  public static byte[] evidenceBytes(Evidence evidence) {
    return FRAMING.evidenceBytes(
        EVIDENCE_V1,
        requestBytes(evidence.request()),
        evidence.snapshotId(),
        evidence.catalog().digest(),
        evidence.catalog().exactBytes(),
        "Oversized complete Social catalog evidence");
  }

  public static Evidence evidenceFromStored(byte[] bytes) {
    try {
      var frame =
          FRAMING.readEvidence(
              bytes,
              EVIDENCE_V1,
              SocialRuntimeFamilyCatalog::requireUuid,
              "Changed/noncanonical Social catalog evidence");
      Request request = requestFromStored(frame.request());
      var catalog = SocialRuntimeFamilyCatalog.fromStored(frame.catalog());
      Evidence result = new Evidence(request, frame.snapshotId(), catalog);
      if (!frame.digest().equals(catalog.digest())) {
        throw new IllegalArgumentException("Changed/noncanonical Social catalog evidence");
      }
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, evidenceBytes(result), "Changed/noncanonical Social catalog evidence");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete Social catalog evidence", invalid);
    }
  }

  public static ReadSocialRuntimeFamilyCatalogRequest toRequest(Request request) {
    return ReadSocialRuntimeFamilyCatalogRequest.newBuilder()
        .addCanonicalRequestBytes(ByteString.copyFrom(requestBytes(request)))
        .build();
  }

  /** Receiver must authenticate its workload before invoking this decoder. */
  public static Request fromRequest(ReadSocialRuntimeFamilyCatalogRequest request) {
    if (request == null
        || request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getCanonicalRequestBytesCount() != 1) {
      throw new IllegalArgumentException("One bounded closed Social catalog request required");
    }
    return requestFromStored(request.getCanonicalRequestBytes(0).toByteArray());
  }

  public static ReadSocialRuntimeFamilyCatalogResponse toResponse(
      Request request, Evidence evidence) {
    if (!request.equals(evidence.request())) {
      throw new IllegalArgumentException("Changed Social catalog read correlation");
    }
    var response =
        ReadSocialRuntimeFamilyCatalogResponse.newBuilder()
            .addCanonicalResponseBytes(ByteString.copyFrom(evidenceBytes(evidence)))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("Oversized Social catalog response");
    }
    return response;
  }

  public static Evidence fromResponse(
      Request request, ReadSocialRuntimeFamilyCatalogResponse response) {
    if (response == null
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getCanonicalResponseBytesCount() != 1) {
      throw new IllegalArgumentException("One complete bounded Social catalog response required");
    }
    Evidence result = evidenceFromStored(response.getCanonicalResponseBytes(0).toByteArray());
    if (!request.equals(result.request())) {
      throw new IllegalArgumentException("Changed Social catalog read correlation");
    }
    return result;
  }
}
