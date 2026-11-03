package net.firedevops.firemud.common.gamesession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.gamesession.GameSessionRuntimeFamilyCatalog.Request;

/** Purpose-specific LOCAL GCR1/GCE1 frames; no proto, self-RPC or compatibility decoder. */
public final class GameSessionRuntimeFamilyCatalogCodec {
  public static final int MAX_REQUEST_BYTES = 1024;
  public static final int MAX_EVIDENCE_BYTES =
      GameSessionRuntimeFamilyCatalog.MAX_CATALOG_BYTES + 4096;
  private static final int REQUEST_V1 = 0x47435231;
  private static final int EVIDENCE_V1 = 0x47434531;

  private GameSessionRuntimeFamilyCatalogCodec() {}

  public static byte[] requestBytes(Request request) {
    Objects.requireNonNull(request, "request");
    return encode(
        out -> {
          out.writeInt(REQUEST_V1);
          uuid(out, request.readRequestId());
          bytes(out, request.targetNamespace().getBytes(StandardCharsets.US_ASCII));
        });
  }

  public static Request requestFromStored(byte[] bytes) {
    try (var in = input(bytes, MAX_REQUEST_BYTES)) {
      if (in.readInt() != REQUEST_V1)
        throw new IllegalArgumentException("Unsupported local read schema");
      var result = new Request(uuid(in), new String(bytes(in, 63), StandardCharsets.US_ASCII));
      if (in.available() != 0 || !Arrays.equals(bytes, requestBytes(result)))
        throw new IllegalArgumentException("Noncanonical Game Session catalog request");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete local catalog request", invalid);
    }
  }

  public static byte[] evidenceBytes(Evidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    byte[] result =
        encode(
            out -> {
              out.writeInt(EVIDENCE_V1);
              bytes(out, requestBytes(evidence.request()));
              uuid(out, evidence.snapshotId());
              bytes(out, evidence.catalog().digest().getBytes(StandardCharsets.US_ASCII));
              bytes(out, evidence.catalog().exactBytes());
            });
    if (result.length > MAX_EVIDENCE_BYTES)
      throw new IllegalArgumentException("Oversized complete catalog evidence");
    return result;
  }

  public static Evidence evidenceFromStored(byte[] bytes) {
    try (var in = input(bytes, MAX_EVIDENCE_BYTES)) {
      if (in.readInt() != EVIDENCE_V1)
        throw new IllegalArgumentException("Unsupported local evidence schema");
      Request request = requestFromStored(bytes(in, MAX_REQUEST_BYTES));
      UUID snapshot = uuid(in);
      String digest = new String(bytes(in, 71), StandardCharsets.US_ASCII);
      var catalog =
          GameSessionRuntimeFamilyCatalog.fromStored(
              bytes(in, GameSessionRuntimeFamilyCatalog.MAX_CATALOG_BYTES));
      var result = new Evidence(request, snapshot, catalog);
      if (in.available() != 0
          || !digest.equals(catalog.digest())
          || !Arrays.equals(bytes, evidenceBytes(result)))
        throw new IllegalArgumentException("Changed/noncanonical local catalog evidence");
      return result;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Incomplete local catalog evidence", invalid);
    }
  }

  private static DataInputStream input(byte[] bytes, int maximum) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum)
      throw new IllegalArgumentException("Frame bound exceeded");
    return new DataInputStream(new ByteArrayInputStream(bytes));
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
    var value = new UUID(in.readLong(), in.readLong());
    GameSessionRuntimeFamilyCatalog.requireUuid(value);
    return value;
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
