package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.FrameReader;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.gamesession.v1.AcquirePrivatePreparationProtectionRequest;
import net.firedevops.firemud.gamesession.v1.PrivatePreparationProtectionResponse;
import net.firedevops.firemud.gamesession.v1.ReadCurrentPrivatePreparationProtectionRequest;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationRequest;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;

/** Closed World-purpose pending-protection transport. No release or actor authority is encoded. */
public final class PrivatePreparationProtectionGrpcCodec {
  public static final int MAX_WIRE_BYTES = 72 * 1024;
  public static final int DEADLINE_SECONDS = 5;
  private static final String ACQUIRE = "game-session-private-protection-acquire/v1";
  private static final String READ = "game-session-private-protection-current/v1";
  private static final String RESPONSE = "game-session-private-protection-response/v1";

  private PrivatePreparationProtectionGrpcCodec() {}

  public record Acquire(UUID requestId, Evidence expectedSource) {
    public Acquire {
      correlation(requestId);
      Objects.requireNonNull(expectedSource);
    }
  }

  public record Read(
      UUID readRequestId, UUID requestId, UUID holdId, long fence, String sourceDigest) {
    public Read {
      correlation(readRequestId);
      uuid(requestId);
      uuid(holdId);
      if (fence <= 0) throw invalid();
      if (sourceDigest == null || !sourceDigest.matches("sha256:[0-9a-f]{64}")) throw invalid();
    }
  }

  public record Held(UUID requestId, UUID holdId, long fence, Evidence source) {
    public Held {
      uuid(requestId);
      uuid(holdId);
      if (fence <= 0) throw invalid();
      Objects.requireNonNull(source);
    }

    public String sourceDigest() {
      return PrivateWorldPreparationReadEvidence.sha256(
          PrivateWorldPreparationReadGrpcCodec.stableBytes(source.snapshot()));
    }

    public boolean admissionAllowed() {
      return false;
    }
  }

  public static AcquirePrivatePreparationProtectionRequest toWire(Acquire request) {
    var wire =
        AcquirePrivatePreparationProtectionRequest.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(bytes(request)))
            .build();
    bound(wire.getSerializedSize());
    return wire;
  }

  public static ReadCurrentPrivatePreparationProtectionRequest toWire(Read request) {
    var wire =
        ReadCurrentPrivatePreparationProtectionRequest.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(bytes(request)))
            .build();
    bound(wire.getSerializedSize());
    return wire;
  }

  public static Acquire parse(AcquirePrivatePreparationProtectionRequest wire) {
    if (wire == null || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
    bound(wire.getSerializedSize());
    byte[] original = wire.getCanonicalEvidence().toByteArray();
    var reader = new FrameReader(original);
    reader.expect(ACQUIRE);
    UUID requestId = uuid(reader.text());
    var result = new Acquire(requestId, source(reader));
    reader.requireEnd();
    if (!Arrays.equals(original, bytes(result))) throw invalid();
    return result;
  }

  public static Read parse(ReadCurrentPrivatePreparationProtectionRequest wire) {
    if (wire == null || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
    bound(wire.getSerializedSize());
    byte[] original = wire.getCanonicalEvidence().toByteArray();
    var reader = new FrameReader(original);
    reader.expect(READ);
    var result =
        new Read(
            uuid(reader.text()),
            uuid(reader.text()),
            uuid(reader.text()),
            positive(reader.text()),
            reader.text());
    reader.requireEnd();
    if (!Arrays.equals(original, bytes(result))) throw invalid();
    return result;
  }

  public static PrivatePreparationProtectionResponse response(Acquire request, Held held) {
    requireSource(request, held);
    return response(ACQUIRE, bytes(request), held);
  }

  public static PrivatePreparationProtectionResponse response(Read request, Held held) {
    requireRead(request, held);
    return response(READ, bytes(request), held);
  }

  public static Held fromResponse(Acquire request, PrivatePreparationProtectionResponse wire) {
    Held held = parseResponse(ACQUIRE, bytes(request), wire);
    requireSource(request, held);
    return held;
  }

  public static Held fromResponse(Read request, PrivatePreparationProtectionResponse wire) {
    Held held = parseResponse(READ, bytes(request), wire);
    requireRead(request, held);
    return held;
  }

  private static void requireSource(Acquire request, Held held) {
    if (!request.requestId().equals(held.requestId())
        || !Arrays.equals(
            PrivateWorldPreparationReadGrpcCodec.stableBytes(request.expectedSource().snapshot()),
            PrivateWorldPreparationReadGrpcCodec.stableBytes(held.source().snapshot())))
      throw invalid();
  }

  private static void requireRead(Read request, Held held) {
    if (!request.requestId().equals(held.requestId())
        || !request.holdId().equals(held.holdId())
        || request.fence() != held.fence()
        || !request.sourceDigest().equals(held.sourceDigest())) throw invalid();
  }

  private static PrivatePreparationProtectionResponse response(
      String operation, byte[] request, Held held) {
    var out = new ByteArrayOutputStream();
    frame(out, RESPONSE);
    frame(out, operation);
    frame(out, PrivateWorldPreparationReadEvidence.sha256(request));
    frame(out, held.requestId().toString());
    frame(out, held.holdId().toString());
    frame(out, Long.toString(held.fence()));
    source(out, held.source());
    var wire =
        PrivatePreparationProtectionResponse.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(out.toByteArray()))
            .build();
    bound(wire.getSerializedSize());
    return wire;
  }

  private static Held parseResponse(
      String operation, byte[] request, PrivatePreparationProtectionResponse wire) {
    if (wire == null || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
    bound(wire.getSerializedSize());
    var reader = new FrameReader(wire.getCanonicalEvidence().toByteArray());
    reader.expect(RESPONSE);
    reader.expect(operation);
    reader.expect(PrivateWorldPreparationReadEvidence.sha256(request));
    var held =
        new Held(uuid(reader.text()), uuid(reader.text()), positive(reader.text()), source(reader));
    reader.requireEnd();
    if (!wire.equals(response(operation, request, held))) throw invalid();
    return held;
  }

  private static byte[] bytes(Acquire request) {
    Objects.requireNonNull(request);
    var out = new ByteArrayOutputStream();
    frame(out, ACQUIRE);
    frame(out, request.requestId().toString());
    source(out, request.expectedSource());
    return out.toByteArray();
  }

  private static byte[] bytes(Read request) {
    Objects.requireNonNull(request);
    var out = new ByteArrayOutputStream();
    frame(out, READ);
    frame(out, request.readRequestId().toString());
    frame(out, request.requestId().toString());
    frame(out, request.holdId().toString());
    frame(out, Long.toString(request.fence()));
    frame(out, request.sourceDigest());
    return out.toByteArray();
  }

  private static void source(ByteArrayOutputStream out, Evidence source) {
    DraftAuthorizationFenceBinding.frame(out, source.request().canonicalBytes());
    DraftAuthorizationFenceBinding.frame(
        out,
        PrivateWorldPreparationReadGrpcCodec.toResponse(source.request(), source.snapshot())
            .getCanonicalEvidence()
            .toByteArray());
  }

  private static Evidence source(FrameReader reader) {
    var request =
        PrivateWorldPreparationReadGrpcCodec.parseRequest(
            ReadPrivateWorldPreparationRequest.newBuilder()
                .setCanonicalEvidence(ByteString.copyFrom(reader.bytes()))
                .build());
    return PrivateWorldPreparationReadGrpcCodec.fromResponse(
        request,
        ReadPrivateWorldPreparationResponse.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(reader.bytes()))
            .build());
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void bound(int size) {
    if (size <= 0 || size > MAX_WIRE_BYTES) throw invalid();
  }

  private static void correlation(UUID id) {
    uuid(id);
    if (id.version() != 4 || id.variant() != 2) throw invalid();
  }

  private static void uuid(UUID id) {
    PrivateWorldPreparationReadEvidence.uuid(id);
  }

  private static UUID uuid(String text) {
    UUID id = UUID.fromString(text);
    uuid(id);
    if (!id.toString().equals(text)) throw invalid();
    return id;
  }

  private static long positive(String value) {
    if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
    return Long.parseLong(value);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid private source protection evidence");
  }
}
