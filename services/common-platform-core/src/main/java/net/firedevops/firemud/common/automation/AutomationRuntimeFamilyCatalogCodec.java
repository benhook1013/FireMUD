package net.firedevops.firemud.common.automation;

import com.google.protobuf.ByteString;
import java.io.IOException;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.runtimecatalog.RuntimeFamilyCatalogFraming;

/** Purpose-specific closed framing; no compatibility, generic parser or numeric fallback. */
public final class AutomationRuntimeFamilyCatalogCodec {
  public static final int MAX_REQUEST_BYTES = 1024;
  public static final int MAX_RESPONSE_BYTES =
      AutomationRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 4096;
  private static final int REQUEST_V1 = 0x41435231;
  private static final int EVIDENCE_V1 = 0x41434531;

  private static final RuntimeFamilyCatalogFraming FRAMING =
      new RuntimeFamilyCatalogFraming(
          MAX_REQUEST_BYTES,
          MAX_RESPONSE_BYTES,
          AutomationRuntimeFamilyCatalog.MAX_CATALOG_BYTES,
          "Frame bound exceeded",
          "Invalid field bound");

  private AutomationRuntimeFamilyCatalogCodec() {}

  public static byte[] requestBytes(Request request) {
    return FRAMING.requestBytes(REQUEST_V1, request.readRequestId(), request.targetNamespace());
  }

  public static Request requestFromStored(byte[] bytes) {
    try {
      var frame =
          FRAMING.readRequest(
              bytes,
              REQUEST_V1,
              AutomationRuntimeFamilyCatalog::requireUuid,
              "Noncanonical Automation catalog request");
      Request result = new Request(frame.requestId(), frame.namespace());
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, requestBytes(result), "Noncanonical Automation catalog request");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete Automation catalog request", invalid);
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
              AutomationRuntimeFamilyCatalog::requireUuid,
              "Changed/noncanonical catalog evidence");
      Request request = requestFromStored(frame.request());
      var catalog = AutomationRuntimeFamilyCatalog.fromStored(frame.catalog());
      Evidence result = new Evidence(request, frame.snapshotId(), catalog);
      if (!frame.digest().equals(catalog.digest())) {
        throw new IllegalArgumentException("Changed/noncanonical catalog evidence");
      }
      RuntimeFamilyCatalogFraming.requireCanonical(
          bytes, evidenceBytes(result), "Changed/noncanonical catalog evidence");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete Automation catalog evidence", invalid);
    }
  }

  public static ReadAutomationRuntimeFamilyCatalogRequest toRequest(Request request) {
    return ReadAutomationRuntimeFamilyCatalogRequest.newBuilder()
        .addCanonicalRequestBytes(ByteString.copyFrom(requestBytes(request)))
        .build();
  }

  /** Authenticate the exact workload before invoking this decoder. */
  public static Request fromRequest(ReadAutomationRuntimeFamilyCatalogRequest request) {
    if (request == null
        || request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getCanonicalRequestBytesCount() != 1) {
      throw new IllegalArgumentException("One bounded closed catalog request required");
    }
    return requestFromStored(request.getCanonicalRequestBytes(0).toByteArray());
  }

  public static ReadAutomationRuntimeFamilyCatalogResponse toResponse(
      Request request, Evidence evidence) {
    if (!request.equals(evidence.request()))
      throw new IllegalArgumentException("Changed catalog read correlation");
    var response =
        ReadAutomationRuntimeFamilyCatalogResponse.newBuilder()
            .addCanonicalResponseBytes(ByteString.copyFrom(evidenceBytes(evidence)))
            .build();
    if (response.getSerializedSize() > MAX_RESPONSE_BYTES)
      throw new IllegalArgumentException("Oversized response");
    return response;
  }

  public static Evidence fromResponse(
      Request request, ReadAutomationRuntimeFamilyCatalogResponse response) {
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
