package net.firedevops.firemud.common.gamedesign;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/** Immutable original owner result. Decoding establishes integrity, never transport authority. */
public final class GameDesignPublicationTerminalEvidence {
  public static final String SCHEMA = "game-design-publication-terminal/v1";
  public static final String RELEASE_SCHEMA = "game-design-published-release-bundle/v1";

  public enum Outcome {
    PUBLISHED,
    NO_PUBLICATION
  }

  /** Lossless participant content, including the presence of nullable publication result fields. */
  public record Participant(
      String participantKey,
      String scopeValue,
      Long baseVersionId,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion,
      String abilitySchemaDigest,
      String errorCode,
      String errorMessage) {
    public Participant {
      new AuthoredWorldReleaseAttestationEvidence.Participant(
          participantKey,
          scopeValue,
          baseVersionId != null,
          baseVersionId,
          appliedCommitId,
          contentDigest,
          digestSchemaVersion,
          abilitySchemaDigest != null,
          abilitySchemaDigest);
      if (errorCode != null && !errorCode.isEmpty()) {
        throw new IllegalArgumentException("Published participant cannot carry an error");
      }
      if (errorMessage != null) utf8(errorMessage);
    }
  }

  /** Complete full-Version content; private SQL aliases and publishedAt are excluded. */
  public record ReleaseContent(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String publishedReleaseBundleRef,
      int versionNumber,
      String attestationSchemaVersion,
      String publishWorkflowId,
      String manifestHash,
      int manifestSchemaVersion,
      List<AuthoredWorldReleaseAttestationEvidence.Artifact> artifactDigests,
      List<String> requiredManifestAssetKeys,
      List<Participant> participantDigests,
      List<String> commandDefinitions,
      String generationConfigRevision,
      WorldPublishedStartLocationEvidence worldStartLocationEvidence) {
    public ReleaseContent {
      uuid(canonicalTenantId);
      uuid(canonicalVersionId);
      text(publishedReleaseBundleRef);
      text(publishWorkflowId);
      text(generationConfigRevision);
      int selectedAttestationSchema =
          switch (attestationSchemaVersion) {
            case "v2" -> AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION;
            case "v3" -> AuthoredWorldReleaseAttestationEvidence.CLOSURE_SELECTOR_SCHEMA_VERSION;
            case "v4" -> AuthoredWorldReleaseAttestationEvidence.SELECTED_FULL_SCHEMA_VERSION;
            default ->
                throw new IllegalArgumentException(
                    "Complete full-Version bundle/v2, bundle/v3, or bundle/v4 required");
          };
      if (versionNumber <= 0 || manifestSchemaVersion != 1) {
        throw new IllegalArgumentException(
            "Complete full-Version bundle/v2, bundle/v3, or bundle/v4 required");
      }
      digest(manifestHash);
      artifactDigests = List.copyOf(artifactDigests);
      requiredManifestAssetKeys = List.copyOf(requiredManifestAssetKeys);
      participantDigests = List.copyOf(participantDigests);
      commandDefinitions = List.copyOf(commandDefinitions);
      commandDefinitions.forEach(GameDesignPublicationTerminalEvidence::utf8);
      worldStartLocationEvidence =
          WorldPublishedStartLocationEvidence.fromStored(
              Objects.requireNonNull(worldStartLocationEvidence).canonicalBytes());
      var selected = worldStartLocationEvidence.request();
      if (!canonicalTenantId.equals(selected.canonicalTenantId())
          || !canonicalVersionId.equals(selected.canonicalVersionId())
          || !publishWorkflowId.equals(selected.publishWorkflowId())) {
        throw new IllegalArgumentException("Release identity differs from World checkpoint");
      }
      var owners = AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder();
      if (participantDigests.size() != owners.size()) {
        throw new IllegalArgumentException("Exactly five participant owners required");
      }
      for (int i = 0; i < owners.size(); i++) {
        var p = participantDigests.get(i);
        if (!owners.get(i).equals(p.participantKey())
            || p.digestSchemaVersion()
                != AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                    p.participantKey(), selectedAttestationSchema)
            || p.baseVersionId() != null
            || !selected.appliedCommitId().equals(p.appliedCommitId())
            || !p.scopeValue().matches("[1-9][0-9]*")
            || new BigInteger(p.scopeValue()).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
            || ((p.abilitySchemaDigest() != null) != "GAME_LOGIC".equals(p.participantKey()))) {
          throw new IllegalArgumentException("Incomplete or changed required participant proof");
        }
      }
      var world = participantDigests.getFirst();
      if (!world.contentDigest().equals(selected.contentDigest())
          || world.digestSchemaVersion() != selected.digestSchemaVersion()) {
        throw new IllegalArgumentException("World participant differs from frozen checkpoint");
      }
      ordered(requiredManifestAssetKeys);
      ordered(
          artifactDigests.stream()
              .map(AuthoredWorldReleaseAttestationEvidence.Artifact::usageKey)
              .toList());
      if (!artifactDigests.stream()
          .map(AuthoredWorldReleaseAttestationEvidence.Artifact::usageKey)
          .toList()
          .containsAll(requiredManifestAssetKeys)) {
        throw new IllegalArgumentException(
            "Every required asset must have actual-byte artifact proof");
      }
    }

    public byte[] canonicalBytes() {
      var out = new ByteArrayOutputStream();
      segment(out, RELEASE_SCHEMA);
      segment(out, canonicalTenantId.toString());
      segment(out, canonicalVersionId.toString());
      segment(out, publishedReleaseBundleRef);
      segment(out, Integer.toString(versionNumber));
      segment(out, attestationSchemaVersion);
      segment(out, publishWorkflowId);
      segment(out, manifestHash);
      segment(out, Integer.toString(manifestSchemaVersion));
      segment(out, Integer.toString(artifactDigests.size()));
      for (var a : artifactDigests) {
        var entry = new ByteArrayOutputStream();
        segment(entry, a.usageKey());
        segment(entry, a.artifactKind());
        segment(entry, a.immutableObjectKey());
        segment(entry, a.contentDigest());
        segment(entry, a.contentType());
        segment(entry, Integer.toString(a.artifactSchemaVersion()));
        segment(out, entry.toByteArray());
      }
      strings(out, requiredManifestAssetKeys);
      segment(out, Integer.toString(participantDigests.size()));
      for (var p : participantDigests) {
        var entry = new ByteArrayOutputStream();
        segment(entry, p.participantKey());
        segment(entry, p.scopeValue());
        optional(entry, p.baseVersionId() == null ? null : p.baseVersionId().toString());
        segment(entry, p.appliedCommitId());
        segment(entry, p.contentDigest());
        segment(entry, Integer.toString(p.digestSchemaVersion()));
        optional(entry, p.abilitySchemaDigest());
        optional(entry, p.errorCode());
        optional(entry, p.errorMessage());
        segment(out, entry.toByteArray());
      }
      strings(out, commandDefinitions);
      segment(out, generationConfigRevision);
      segment(out, worldStartLocationEvidence.canonicalBytes());
      // Full versions cannot carry a script patch; explicit presence is still part of content.
      segment(out, "false");
      optional(out, null);
      return out.toByteArray();
    }

    public String contentDigest() {
      return sha256(canonicalBytes());
    }

    public static ReleaseContent fromStored(byte[] bytes) {
      var r = new Reader(bytes);
      r.expect(RELEASE_SCHEMA);
      UUID tenant = UUID.fromString(r.text());
      UUID version = UUID.fromString(r.text());
      String ref = r.text();
      int number = r.positiveInt();
      String schema = r.text();
      String workflow = r.text();
      String manifest = r.text();
      int manifestSchema = r.positiveInt();
      var artifacts = new ArrayList<AuthoredWorldReleaseAttestationEvidence.Artifact>();
      for (int remaining = r.count(); remaining > 0; remaining--) {
        var a = new Reader(r.bytes());
        artifacts.add(
            new AuthoredWorldReleaseAttestationEvidence.Artifact(
                a.text(), a.text(), a.text(), a.text(), a.text(), a.positiveInt()));
        a.end();
      }
      List<String> required = r.strings();
      var participants = new ArrayList<Participant>();
      for (int remaining = r.count(); remaining > 0; remaining--) {
        var p = new Reader(r.bytes());
        String owner = p.text();
        String scope = p.text();
        String base = p.optional();
        participants.add(
            new Participant(
                owner,
                scope,
                base == null ? null : positiveLong(base),
                p.text(),
                p.text(),
                p.positiveInt(),
                p.optional(),
                p.optional(),
                p.optional()));
        p.end();
      }
      List<String> commands = r.strings();
      String generation = r.text();
      var world = WorldPublishedStartLocationEvidence.fromStored(r.bytes());
      r.expect("false");
      if (r.optional() != null) throw new IllegalArgumentException("Full version has script patch");
      r.end();
      var result =
          new ReleaseContent(
              tenant,
              version,
              ref,
              number,
              schema,
              workflow,
              manifest,
              manifestSchema,
              artifacts,
              required,
              participants,
              commands,
              generation,
              world);
      if (!Arrays.equals(bytes, result.canonicalBytes()))
        throw new IllegalArgumentException("Noncanonical release content");
      return result;
    }
  }

  private final GameDesignPublicationOperationBinding operation;
  private final Outcome outcome;
  private final ReleaseContent release;
  private final Long epoch;

  public GameDesignPublicationTerminalEvidence(
      byte[] operationBytes,
      Outcome outcome,
      ReleaseContent release,
      Long publicationVersionStateEpoch) {
    this.operation = GameDesignPublicationOperationBinding.fromStored(operationBytes);
    this.outcome = Objects.requireNonNull(outcome);
    if ((outcome == Outcome.PUBLISHED) != (release != null && publicationVersionStateEpoch != null)
        || (outcome == Outcome.NO_PUBLICATION
            && (release != null || publicationVersionStateEpoch != null))) {
      throw new IllegalArgumentException("Outcome-inapplicable release or epoch");
    }
    this.release = release == null ? null : ReleaseContent.fromStored(release.canonicalBytes());
    this.epoch = publicationVersionStateEpoch;
    if (release != null) {
      if (epoch <= 0
          || epoch != Math.addExact(operation.world().request().versionStateEpoch(), 1L)
          || !Arrays.equals(
              release.worldStartLocationEvidence().canonicalBytes(),
              operation.world().canonicalBytes())) {
        throw new IllegalArgumentException(
            "Terminal release differs from original publication operation");
      }
      String scope =
          Long.toString(operation.account().input().selection().target().gameDesignVersionRowId());
      if (release.participantDigests().stream().anyMatch(p -> !scope.equals(p.scopeValue()))) {
        throw new IllegalArgumentException("Participant scope differs from selected owner Version");
      }
    }
  }

  public Outcome outcome() {
    return outcome;
  }

  public byte[] operationBytes() {
    return operation.canonicalBytes();
  }

  public WorldPublishedStartLocationEvidence worldEvidence() {
    return operation.world();
  }

  public String publishedReleaseBundleRef() {
    return requireRelease().publishedReleaseBundleRef();
  }

  public String publishedReleaseBundleDigest() {
    return requireRelease().contentDigest();
  }

  public long publicationVersionStateEpoch() {
    requireRelease();
    return epoch;
  }

  public ReleaseContent releaseContent() {
    return requireRelease();
  }

  private ReleaseContent requireRelease() {
    if (release == null)
      throw new IllegalStateException(
          "NO_PUBLICATION has no release or committed publication epoch");
    return release;
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, operationBytes());
    DraftAuthorizationFenceBinding.frame(out, outcome.name());
    if (release != null) {
      DraftAuthorizationFenceBinding.frame(out, release.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, Long.toString(epoch));
    }
    return out.toByteArray();
  }

  public static GameDesignPublicationTerminalEvidence fromStored(byte[] bytes) {
    var r = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    r.expect(SCHEMA);
    byte[] operation = r.bytes();
    Outcome outcome = Outcome.valueOf(r.text());
    ReleaseContent release =
        outcome == Outcome.PUBLISHED ? ReleaseContent.fromStored(r.bytes()) : null;
    Long epoch = outcome == Outcome.PUBLISHED ? positiveLong(r.text()) : null;
    r.requireEnd();
    var result = new GameDesignPublicationTerminalEvidence(operation, outcome, release, epoch);
    if (!Arrays.equals(bytes, result.canonicalBytes()))
      throw new IllegalArgumentException("Noncanonical terminal evidence");
    return result;
  }

  private static void uuid(UUID id) {
    if (id == null || id.equals(new UUID(0L, 0L)))
      throw new IllegalArgumentException("Non-nil UUID required");
  }

  private static void text(String s) {
    if (s == null || s.isBlank() || s.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Canonical nonblank text required");
    utf8(s);
  }

  private static void digest(String s) {
    if (s == null || !s.matches("sha256:[0-9a-f]{64}"))
      throw new IllegalArgumentException("SHA256 digest required");
  }

  private static long positiveLong(String s) {
    if (!s.matches("[1-9][0-9]*"))
      throw new IllegalArgumentException("Canonical positive decimal required");
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Counter exceeds owner range", e);
    }
  }

  private static byte[] utf8(String s) {
    try {
      var b =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(Objects.requireNonNull(s)));
      var bytes = new byte[b.remaining()];
      b.get(bytes);
      return bytes;
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Invalid UTF-8", e);
    }
  }

  private static void ordered(List<String> values) {
    String prior = null;
    for (String value : values) {
      text(value);
      if (prior != null && Arrays.compareUnsigned(utf8(prior), utf8(value)) >= 0)
        throw new IllegalArgumentException("Array must be unique and unsigned UTF-8 ordered");
      prior = value;
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void segment(ByteArrayOutputStream out, String value) {
    segment(out, utf8(value));
  }

  private static void segment(ByteArrayOutputStream out, byte[] bytes) {
    out.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    out.write(':');
    out.writeBytes(bytes);
  }

  private static void optional(ByteArrayOutputStream out, String value) {
    segment(out, value == null ? "false" : "true");
    segment(out, value == null ? "" : value);
  }

  private static void strings(ByteArrayOutputStream out, List<String> values) {
    segment(out, Integer.toString(values.size()));
    values.forEach(v -> segment(out, v));
  }

  private static final class Reader {
    private final byte[] bytes;
    private int offset;

    Reader(byte[] bytes) {
      this.bytes = Objects.requireNonNull(bytes).clone();
    }

    byte[] bytes() {
      int start = offset;
      while (offset < bytes.length && bytes[offset] >= '0' && bytes[offset] <= '9') offset++;
      if (start == offset || offset == bytes.length || bytes[offset] != ':')
        throw new IllegalArgumentException("Malformed byte-length frame");
      String decimal = new String(bytes, start, offset - start, StandardCharsets.US_ASCII);
      if (!decimal.matches("0|[1-9][0-9]*"))
        throw new IllegalArgumentException("Noncanonical frame length");
      int length;
      try {
        length = new BigInteger(decimal).intValueExact();
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException("Frame too large", e);
      }
      offset++;
      if (length > bytes.length - offset) throw new IllegalArgumentException("Truncated frame");
      byte[] value = Arrays.copyOfRange(bytes, offset, offset + length);
      offset += length;
      return value;
    }

    String text() {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes()))
            .toString();
      } catch (CharacterCodingException e) {
        throw new IllegalArgumentException("Invalid UTF-8", e);
      }
    }

    void expect(String value) {
      if (!value.equals(text())) throw new IllegalArgumentException("Unexpected canonical field");
    }

    int positiveInt() {
      return Math.toIntExact(positiveLong(text()));
    }

    int count() {
      String s = text();
      if (!s.matches("0|[1-9][0-9]*"))
        throw new IllegalArgumentException("Noncanonical array count");
      int n;
      try {
        n = new BigInteger(s).intValueExact();
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException("Array too large", e);
      }
      if (n > (bytes.length - offset) / 2) throw new IllegalArgumentException("Incomplete array");
      return n;
    }

    String optional() {
      String present = text();
      String value = text();
      if ("true".equals(present)) return value;
      if (!"false".equals(present) || !value.isEmpty())
        throw new IllegalArgumentException("Malformed optional field");
      return null;
    }

    List<String> strings() {
      var result = new ArrayList<String>();
      for (int n = count(); n > 0; n--) result.add(text());
      return result;
    }

    void end() {
      if (offset != bytes.length) throw new IllegalArgumentException("Trailing fields");
    }
  }
}
