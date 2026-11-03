package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
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

  private WorldRuntimeFamilyCatalogCodec() {}

  public static byte[] requestBytes(Request request) {
    return encode(
        out -> {
          out.writeInt(REQUEST_V1);
          uuid(out, request.readRequestId());
          bytes(out, request.targetNamespace().getBytes(StandardCharsets.US_ASCII));
        });
  }

  public static Request requestFromStored(byte[] bytes) {
    try (var in = input(bytes, MAX_REQUEST_BYTES)) {
      if (in.readInt() != REQUEST_V1) throw new IllegalArgumentException("Unsupported read schema");
      UUID id = uuid(in);
      byte[] namespaceBytes = bytes(in, 63);
      String namespace = new String(namespaceBytes, StandardCharsets.US_ASCII);
      Request result = new Request(id, namespace);
      if (in.available() != 0 || !Arrays.equals(bytes, requestBytes(result))) {
        throw new IllegalArgumentException("Noncanonical World catalog request");
      }
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete World catalog request", invalid);
    }
  }

  public static byte[] evidenceBytes(Evidence evidence) {
    byte[] result =
        encode(
            out -> {
              out.writeInt(EVIDENCE_V1);
              bytes(out, requestBytes(evidence.request()));
              uuid(out, evidence.snapshotId());
              bytes(out, evidence.catalog().digest().getBytes(StandardCharsets.US_ASCII));
              bytes(out, evidence.catalog().exactBytes());
            });
    if (result.length > MAX_RESPONSE_BYTES)
      throw new IllegalArgumentException("Oversized complete catalog evidence");
    return result;
  }

  public static Evidence evidenceFromStored(byte[] bytes) {
    try (var in = input(bytes, MAX_RESPONSE_BYTES)) {
      if (in.readInt() != EVIDENCE_V1)
        throw new IllegalArgumentException("Unsupported evidence schema");
      Request request = requestFromStored(bytes(in, MAX_REQUEST_BYTES));
      UUID snapshotId = uuid(in);
      String digest = new String(bytes(in, 71), StandardCharsets.US_ASCII);
      WorldRuntimeFamilyCatalog catalog =
          WorldRuntimeFamilyCatalog.fromStored(
              bytes(in, WorldRuntimeFamilyCatalog.MAX_CATALOG_BYTES));
      Evidence result = new Evidence(request, snapshotId, catalog);
      if (in.available() != 0
          || !digest.equals(catalog.digest())
          || !Arrays.equals(bytes, evidenceBytes(result))) {
        throw new IllegalArgumentException("Changed/noncanonical catalog evidence");
      }
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

  private static DataInputStream input(byte[] value, int maximum) {
    if (value == null || value.length == 0 || value.length > maximum)
      throw new IllegalArgumentException("Frame bound exceeded");
    return new DataInputStream(new ByteArrayInputStream(value));
  }

  private static byte[] bytes(DataInputStream in, int maximum) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > maximum || length > in.available())
      throw new IllegalArgumentException("Invalid field bound");
    return in.readNBytes(length);
  }

  private static void bytes(DataOutputStream out, byte[] value) throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  private static UUID uuid(DataInputStream in) throws IOException {
    UUID result = new UUID(in.readLong(), in.readLong());
    WorldRuntimeFamilyCatalog.requireUuid(result);
    return result;
  }

  private static void uuid(DataOutputStream out, UUID value) throws IOException {
    out.writeLong(value.getMostSignificantBits());
    out.writeLong(value.getLeastSignificantBits());
  }

  private interface Encoder {
    void write(DataOutputStream out) throws IOException;
  }

  private static byte[] encode(Encoder encoder) {
    try (var buffer = new ByteArrayOutputStream();
        var out = new DataOutputStream(buffer)) {
      encoder.write(out);
      out.flush();
      return buffer.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Memory frame encoding failed", impossible);
    }
  }
}
