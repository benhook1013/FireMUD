package net.firedevops.firemud.common.runtimecatalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Internal framing shared only by the four foreign-owner runtime-family catalog codecs. Public for
 * those separate packages; it defines no new wire format, domain schema or compatibility parser.
 * Owner codecs retain their magic values, domain parsing, digest and correlation checks.
 */
public final class RuntimeFamilyCatalogFraming {
  private final int maximumRequestBytes;
  private final int maximumResponseBytes;
  private final int maximumCatalogBytes;
  private final String frameBoundMessage;
  private final String fieldBoundMessage;

  public RuntimeFamilyCatalogFraming(
      int maximumRequestBytes,
      int maximumResponseBytes,
      int maximumCatalogBytes,
      String frameBoundMessage,
      String fieldBoundMessage) {
    this.maximumRequestBytes = maximumRequestBytes;
    this.maximumResponseBytes = maximumResponseBytes;
    this.maximumCatalogBytes = maximumCatalogBytes;
    this.frameBoundMessage = frameBoundMessage;
    this.fieldBoundMessage = fieldBoundMessage;
  }

  public byte[] requestBytes(int magic, UUID requestId, String namespace) {
    return encode(
        out -> {
          out.writeInt(magic);
          uuid(out, requestId);
          bytes(out, namespace.getBytes(StandardCharsets.US_ASCII));
        });
  }

  public RequestFrame readRequest(
      byte[] value, int magic, Consumer<UUID> validateUuid, String noncanonicalMessage)
      throws IOException {
    try (var in = input(value, maximumRequestBytes)) {
      if (in.readInt() != magic) throw new IllegalArgumentException("Unsupported read schema");
      UUID requestId = uuid(in, validateUuid);
      String namespace = new String(bytes(in, 63), StandardCharsets.US_ASCII);
      requireEnd(in, noncanonicalMessage);
      return new RequestFrame(requestId, namespace);
    }
  }

  public byte[] evidenceBytes(
      int magic,
      byte[] request,
      UUID snapshotId,
      String digest,
      byte[] catalog,
      String oversizedMessage) {
    byte[] result =
        encode(
            out -> {
              out.writeInt(magic);
              bytes(out, request);
              uuid(out, snapshotId);
              bytes(out, digest.getBytes(StandardCharsets.US_ASCII));
              bytes(out, catalog);
            });
    if (result.length > maximumResponseBytes) throw new IllegalArgumentException(oversizedMessage);
    return result;
  }

  public EvidenceFrame readEvidence(
      byte[] value, int magic, Consumer<UUID> validateUuid, String noncanonicalMessage)
      throws IOException {
    try (var in = input(value, maximumResponseBytes)) {
      if (in.readInt() != magic) throw new IllegalArgumentException("Unsupported evidence schema");
      byte[] request = bytes(in, maximumRequestBytes);
      UUID snapshotId = uuid(in, validateUuid);
      String digest = new String(bytes(in, 71), StandardCharsets.US_ASCII);
      byte[] catalog = bytes(in, maximumCatalogBytes);
      requireEnd(in, noncanonicalMessage);
      return new EvidenceFrame(request, snapshotId, digest, catalog);
    }
  }

  public static void requireCanonical(byte[] original, byte[] reencoded, String message) {
    if (!Arrays.equals(original, reencoded)) throw new IllegalArgumentException(message);
  }

  public record RequestFrame(UUID requestId, String namespace) {}

  public record EvidenceFrame(byte[] request, UUID snapshotId, String digest, byte[] catalog) {
    public EvidenceFrame {
      request = request.clone();
      catalog = catalog.clone();
    }

    @Override
    public byte[] request() {
      return request.clone();
    }

    @Override
    public byte[] catalog() {
      return catalog.clone();
    }
  }

  private DataInputStream input(byte[] value, int maximum) {
    if (value == null || value.length == 0 || value.length > maximum)
      throw new IllegalArgumentException(frameBoundMessage);
    return new DataInputStream(new ByteArrayInputStream(value));
  }

  private byte[] bytes(DataInputStream in, int maximum) throws IOException {
    int length = in.readInt();
    if (length < 0 || length > maximum || length > in.available())
      throw new IllegalArgumentException(fieldBoundMessage);
    return in.readNBytes(length);
  }

  private static void bytes(DataOutputStream out, byte[] value) throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  private static UUID uuid(DataInputStream in, Consumer<UUID> validateUuid) throws IOException {
    UUID result = new UUID(in.readLong(), in.readLong());
    validateUuid.accept(result);
    return result;
  }

  private static void uuid(DataOutputStream out, UUID value) throws IOException {
    out.writeLong(value.getMostSignificantBits());
    out.writeLong(value.getLeastSignificantBits());
  }

  private static void requireEnd(DataInputStream in, String message) throws IOException {
    if (in.available() != 0) throw new IllegalArgumentException(message);
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
