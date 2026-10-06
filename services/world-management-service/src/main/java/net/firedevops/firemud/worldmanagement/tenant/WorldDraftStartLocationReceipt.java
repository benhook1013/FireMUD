package net.firedevops.firemud.worldmanagement.tenant;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.RoomTemplateRef;

/** Exact durable selector evidence tied to its original Account operation and stored graph. */
public record WorldDraftStartLocationReceipt(
    String targetNamespace,
    UUID operationId,
    UUID requestId,
    UUID commitId,
    UUID authorizationFenceId,
    String accountBindingDigest,
    String bindingDigest,
    RoomTemplateRef startLocation,
    String graphDigest,
    String receiptDigest) {
  private static final String SCHEMA = "world-draft-start-location-receipt/v1";

  public WorldDraftStartLocationReceipt {
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(authorizationFenceId, "authorizationFenceId");
    Objects.requireNonNull(accountBindingDigest, "accountBindingDigest");
    Objects.requireNonNull(bindingDigest, "bindingDigest");
    Objects.requireNonNull(startLocation, "startLocation");
    Objects.requireNonNull(graphDigest, "graphDigest");
    Objects.requireNonNull(receiptDigest, "receiptDigest");
  }

  static WorldDraftStartLocationReceipt create(
      WorldDraftGraphApplication application, byte[] graphBytes) {
    var operation = application.operation();
    RoomTemplateRef selector =
        application
            .plan()
            .graph()
            .freshGraphDeclaration()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "World start-location receipt requires an original graph declaration"))
            .startLocation();
    String graphDigest = WorldDraftGraphAppliedResult.digest(graphBytes);
    String receiptDigest =
        digest(
            operation.ownerBinding().targetNamespace(),
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.accountBindingDigest(),
            operation.binding().digest(),
            selector,
            graphDigest);
    return new WorldDraftStartLocationReceipt(
        operation.ownerBinding().targetNamespace(),
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId(),
        operation.accountBindingDigest(),
        operation.binding().digest(),
        selector,
        graphDigest,
        receiptDigest);
  }

  byte[] canonicalBytes() {
    Map<String, Object> selector = new LinkedHashMap<>();
    selector.put("tenantId", startLocation.tenantId().toString());
    selector.put("versionId", startLocation.versionId().toString());
    selector.put("roomTemplateId", startLocation.roomTemplateId().toString());
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("schema", SCHEMA);
    json.put("targetNamespace", targetNamespace);
    json.put("operationId", operationId.toString());
    json.put("requestId", requestId.toString());
    json.put("commitId", commitId.toString());
    json.put("authorizationFenceId", authorizationFenceId.toString());
    json.put("accountBindingDigest", accountBindingDigest);
    json.put("bindingDigest", bindingDigest);
    json.put("startLocation", selector);
    json.put("graphDigest", graphDigest);
    json.put("receiptDigest", receiptDigest);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(
          new tools.jackson.databind.ObjectMapper().writeValueAsString(json));
    } catch (java.io.IOException exception) {
      throw new IllegalArgumentException(
          "World start-location receipt cannot be encoded", exception);
    }
  }

  private static String digest(
      String namespace,
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID fenceId,
      String accountBindingDigest,
      String bindingDigest,
      RoomTemplateRef selector,
      String graphDigest) {
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String segment :
        new String[] {
          SCHEMA,
          namespace,
          operationId.toString(),
          requestId.toString(),
          commitId.toString(),
          fenceId.toString(),
          accountBindingDigest,
          bindingDigest,
          selector.tenantId().toString(),
          selector.versionId().toString(),
          selector.roomTemplateId().toString(),
          graphDigest
        }) {
      byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      framed.writeBytes(bytes);
    }
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
