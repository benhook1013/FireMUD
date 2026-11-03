package net.firedevops.firemud.common.social;

import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
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

  private SocialRuntimeFamilyCatalogCodec() {}

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
      String namespace = new String(bytes(in, 63), StandardCharsets.US_ASCII);
      Request result = new Request(id, namespace);
      if (in.available() != 0 || !Arrays.equals(bytes, requestBytes(result))) {
        throw new IllegalArgumentException("Noncanonical Social catalog request");
      }
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete Social catalog request", invalid);
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
    if (result.length > MAX_RESPONSE_BYTES) {
      throw new IllegalArgumentException("Oversized complete Social catalog evidence");
    }
    return result;
  }

  public static Evidence evidenceFromStored(byte[] bytes) {
    try (var in = input(bytes, MAX_RESPONSE_BYTES)) {
      if (in.readInt() != EVIDENCE_V1)
        throw new IllegalArgumentException("Unsupported evidence schema");
      Request request = requestFromStored(bytes(in, MAX_REQUEST_BYTES));
      UUID snapshotId = uuid(in);
      String digest = new String(bytes(in, 71), StandardCharsets.US_ASCII);
      SocialRuntimeFamilyCatalog catalog =
          SocialRuntimeFamilyCatalog.fromStored(
              bytes(in, SocialRuntimeFamilyCatalog.MAX_CATALOG_BYTES));
      Evidence result = new Evidence(request, snapshotId, catalog);
      if (in.available() != 0
          || !digest.equals(catalog.digest())
          || !Arrays.equals(bytes, evidenceBytes(result))) {
        throw new IllegalArgumentException("Changed/noncanonical Social catalog evidence");
      }
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

  private static DataInputStream input(byte[] value, int maximum) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw new IllegalArgumentException("Social catalog frame bound exceeded");
    }
    return new DataInputStream(new ByteArrayInputStream(value));
  }

  private static byte[] bytes(DataInputStream in, int maximum) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > maximum || length > in.available()) {
      throw new IllegalArgumentException("Invalid Social catalog field bound");
    }
    return in.readNBytes(length);
  }

  private static void bytes(DataOutputStream out, byte[] value) throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  private static UUID uuid(DataInputStream in) throws IOException {
    UUID result = new UUID(in.readLong(), in.readLong());
    SocialRuntimeFamilyCatalog.requireUuid(result);
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
