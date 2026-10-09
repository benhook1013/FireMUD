package net.firedevops.firemud.common.authoring;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact immutable source material, not an authorization decision or authenticated actor proof. */
public record DraftAuthorizationFenceBinding(
    UUID operationId,
    UUID requestId,
    UUID commitId,
    UUID fenceId,
    UUID actorAccountId,
    UUID tenantId,
    UUID versionId,
    String baseCommitId,
    String expectedDraftEpoch,
    byte[] gameDesignBinding,
    byte[] normalizedInput,
    String inputDigest,
    List<SourceEvidence> sources,
    String schemaVersion,
    List<Owner> requiredOwners) {

  public static final String SCHEMA = "account-draft-authorization-fence/v1";
  public static final String SCHEMA_V1 = SCHEMA;
  public static final String SCHEMA_V2 = "account-draft-authorization-fence/v2";
  private static final List<Owner> V1_OWNERS = List.of(Owner.GAME_DESIGN, Owner.WORLD);

  /** Retains the original producer's exact v1 frame and fixed owner vector. */
  public DraftAuthorizationFenceBinding(
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID fenceId,
      UUID actorAccountId,
      UUID tenantId,
      UUID versionId,
      String baseCommitId,
      String expectedDraftEpoch,
      byte[] gameDesignBinding,
      byte[] normalizedInput,
      String inputDigest,
      List<SourceEvidence> sources) {
    this(
        operationId,
        requestId,
        commitId,
        fenceId,
        actorAccountId,
        tenantId,
        versionId,
        baseCommitId,
        expectedDraftEpoch,
        gameDesignBinding,
        normalizedInput,
        inputDigest,
        sources,
        SCHEMA_V1,
        V1_OWNERS);
  }

  public DraftAuthorizationFenceBinding {
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    requiredOwners = List.copyOf(Objects.requireNonNull(requiredOwners, "requiredOwners"));
    for (UUID id :
        List.of(operationId, requestId, commitId, fenceId, actorAccountId, tenantId, versionId)) {
      requireUuid(id);
    }
    text(baseCommitId);
    if (baseCommitId.getBytes(StandardCharsets.UTF_8).length > 256) {
      throw new IllegalArgumentException("Base commit exceeds 256 UTF-8 bytes");
    }
    decimal(expectedDraftEpoch, true);
    gameDesignBinding = bytes(gameDesignBinding);
    normalizedInput = bytes(normalizedInput);
    DraftCommitBinding completeBinding;
    try {
      completeBinding =
          DraftCommitBinding.fromStored(
              new String(gameDesignBinding, StandardCharsets.UTF_8), digest(gameDesignBinding));
    } catch (IllegalStateException corrupt) {
      throw new IllegalArgumentException(
          "Complete canonical Game Design binding required", corrupt);
    }
    if (!Arrays.equals(gameDesignBinding, completeBinding.canonicalBytes())
        || !requestId.equals(completeBinding.requestId())
        || !commitId.equals(completeBinding.commitId())
        || !tenantId.equals(completeBinding.target().canonicalTenantId())
        || !versionId.equals(completeBinding.target().canonicalVersionId())
        || !baseCommitId.equals(completeBinding.baseCommitId())) {
      throw new IllegalArgumentException("Account fence differs from complete Game Design binding");
    }
    if (!Arrays.equals(normalizedInput, gameDesignBinding)
        || !completeBinding.digest().equals(inputDigest)) {
      throw new IllegalArgumentException(
          "Draft input must be the exact complete canonical binding");
    }
    sources = List.copyOf(Objects.requireNonNull(sources));
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("Applicable Account sources are required");
    }
    sources = sources.stream().sorted(Comparator.comparing(SourceEvidence::key)).toList();
    if (sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw new IllegalArgumentException("Duplicate Account source scope");
    }
    if (SCHEMA_V1.equals(schemaVersion)) {
      if (!V1_OWNERS.equals(requiredOwners)) {
        throw new IllegalArgumentException("V1 Draft authorization owners are fixed");
      }
    } else if (SCHEMA_V2.equals(schemaVersion)) {
      List<Owner> canonicalOwners = requiredOwners(completeBinding);
      if (!canonicalOwners.equals(requiredOwners)) {
        throw new IllegalArgumentException(
            "V2 Draft authorization owners must exactly match the complete binding");
      }
    } else {
      throw new IllegalArgumentException("Unsupported Draft authorization fence schema");
    }
  }

  /** Converts this exact authorization input to v2 with its canonical complete owner set. */
  public DraftAuthorizationFenceBinding withRequiredOwners() {
    DraftCommitBinding completeBinding = completeGameDesignBinding();
    return new DraftAuthorizationFenceBinding(
        operationId,
        requestId,
        commitId,
        fenceId,
        actorAccountId,
        tenantId,
        versionId,
        baseCommitId,
        expectedDraftEpoch,
        gameDesignBinding,
        normalizedInput,
        inputDigest,
        sources,
        SCHEMA_V2,
        requiredOwners(completeBinding));
  }

  private DraftCommitBinding completeGameDesignBinding() {
    return DraftCommitBinding.fromStored(
        new String(gameDesignBinding, StandardCharsets.UTF_8), digest(gameDesignBinding));
  }

  private static List<Owner> requiredOwners(DraftCommitBinding binding) {
    EnumSet<Owner> owners = EnumSet.of(Owner.GAME_DESIGN);
    for (DraftCommitBinding.Owner owner : binding.requiredOwners()) {
      owners.add(
          switch (owner) {
            case WORLD_MANAGEMENT -> Owner.WORLD;
            case ENTITY_MANAGEMENT -> Owner.ENTITY;
            case GAME_LOGIC -> Owner.GAME_LOGIC;
            case AUTOMATION_SCRIPTING -> Owner.AUTOMATION;
            case GAME_DESIGN_CONTROL_PLANE -> Owner.GAME_DESIGN;
          });
    }
    return Arrays.stream(Owner.values()).filter(owners::contains).toList();
  }

  /**
   * Reconstructs only exact supported immutable Draft-fence framing; this grants no authorization.
   */
  public static DraftAuthorizationFenceBinding fromStored(byte[] original) {
    byte[] stored = bytes(original);
    FrameReader reader = new FrameReader(stored);
    String schemaVersion = reader.text();
    if (!SCHEMA_V1.equals(schemaVersion) && !SCHEMA_V2.equals(schemaVersion)) {
      throw new IllegalArgumentException("Unsupported immutable source schema");
    }
    UUID operationId = canonicalUuidValue(reader.text());
    UUID requestId = canonicalUuidValue(reader.text());
    UUID commitId = canonicalUuidValue(reader.text());
    UUID fenceId = canonicalUuidValue(reader.text());
    UUID actorAccountId = canonicalUuidValue(reader.text());
    UUID tenantId = canonicalUuidValue(reader.text());
    UUID versionId = canonicalUuidValue(reader.text());
    String baseCommitId = reader.text();
    reader.expect("DRAFT");
    String expectedDraftEpoch = reader.text();
    byte[] gameDesignBinding = reader.bytes();
    byte[] normalizedInput = reader.bytes();
    String inputDigest = reader.text();
    String countText = reader.text();
    decimal(countText, false);
    BigInteger sourceCount = new BigInteger(countText);
    if (sourceCount.compareTo(BigInteger.valueOf(reader.remaining() / Integer.BYTES)) > 0) {
      throw new IllegalArgumentException("Incomplete stored source vector");
    }
    int sourceCountValue = sourceCount.intValueExact();
    java.util.ArrayList<SourceEvidence> sources = new java.util.ArrayList<>();
    for (int index = 0; index < sourceCountValue; index++) {
      sources.add(SourceEvidence.fromStored(reader.bytes()));
    }
    List<Owner> requiredOwners;
    if (SCHEMA_V1.equals(schemaVersion)) {
      reader.expect("GAME_DESIGN");
      reader.expect("WORLD");
      requiredOwners = V1_OWNERS;
    } else {
      String ownerCountText = reader.text();
      decimal(ownerCountText, false);
      BigInteger ownerCount = new BigInteger(ownerCountText);
      if (ownerCount.compareTo(BigInteger.valueOf(Owner.values().length)) > 0
          || ownerCount.compareTo(BigInteger.valueOf(reader.remaining() / Integer.BYTES)) > 0) {
        throw new IllegalArgumentException("Invalid stored required-owner vector");
      }
      int ownerCountValue = ownerCount.intValueExact();
      java.util.ArrayList<Owner> storedOwners = new java.util.ArrayList<>();
      for (int index = 0; index < ownerCountValue; index++) {
        storedOwners.add(Owner.valueOf(reader.text()));
      }
      requiredOwners = List.copyOf(storedOwners);
    }
    reader.requireEnd();

    DraftAuthorizationFenceBinding binding =
        new DraftAuthorizationFenceBinding(
            operationId,
            requestId,
            commitId,
            fenceId,
            actorAccountId,
            tenantId,
            versionId,
            baseCommitId,
            expectedDraftEpoch,
            gameDesignBinding,
            normalizedInput,
            inputDigest,
            sources,
            schemaVersion,
            requiredOwners);
    if (!Arrays.equals(stored, binding.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical stored authorization fence binding");
    }
    return binding;
  }

  @Override
  public List<SourceEvidence> sources() {
    return List.copyOf(sources);
  }

  @Override
  public List<Owner> requiredOwners() {
    return List.copyOf(requiredOwners);
  }

  @Override
  public byte[] gameDesignBinding() {
    return gameDesignBinding.clone();
  }

  @Override
  public byte[] normalizedInput() {
    return normalizedInput.clone();
  }

  /** Closed, ordered UTF-8/binary length frames; counters are exact decimal strings. */
  public byte[] canonicalBytes() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    frame(output, schemaVersion);
    for (UUID id :
        List.of(operationId, requestId, commitId, fenceId, actorAccountId, tenantId, versionId)) {
      frame(output, id.toString());
    }
    frame(output, baseCommitId);
    frame(output, "DRAFT");
    frame(output, expectedDraftEpoch);
    frame(output, gameDesignBinding);
    frame(output, normalizedInput);
    frame(output, inputDigest);
    frame(output, Integer.toString(sources.size()));
    for (SourceEvidence source : sources) {
      frame(output, source.canonicalBytes());
    }
    if (SCHEMA_V1.equals(schemaVersion)) {
      frame(output, "GAME_DESIGN");
      frame(output, "WORLD");
    } else {
      frame(output, Integer.toString(requiredOwners.size()));
      for (Owner owner : requiredOwners) {
        frame(output, owner.name());
      }
    }
    return output.toByteArray();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || (other instanceof DraftAuthorizationFenceBinding binding
            && Arrays.equals(canonicalBytes(), binding.canonicalBytes()));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes());
  }

  public enum SourceKind {
    ISSUER,
    ACCOUNT,
    TENANT,
    MEMBERSHIP,
    GLOBAL_ROLES,
    CREATOR_PARTY,
    HOSTED_TERMS
  }

  /**
   * Independent owner counters/checkpoints and exact existing source evidence; no inferred epochs.
   */
  public record SourceEvidence(
      SourceKind kind,
      String scopeId,
      String generation,
      String sourceVersion,
      String checkpointStream,
      String checkpointSequence,
      byte[] evidence) {
    public SourceEvidence {
      Objects.requireNonNull(kind);
      text(scopeId);
      // Preserve the exact configured issuer, including its supported 512-character Unicode
      // identity. Bound the complete index key in bytes rather than truncating or aliasing it.
      if ((kind.name() + ":" + scopeId).getBytes(StandardCharsets.UTF_8).length > 2048) {
        throw new IllegalArgumentException("Source participation key exceeds 2048 UTF-8 bytes");
      }
      if (kind == SourceKind.ACCOUNT
          || kind == SourceKind.TENANT
          || kind == SourceKind.GLOBAL_ROLES) {
        canonicalUuid(scopeId);
      }
      if (kind == SourceKind.MEMBERSHIP) {
        String[] pair = scopeId.split("/", -1);
        if (pair.length != 2) {
          throw new IllegalArgumentException("Canonical membership pair required");
        }
        canonicalUuid(pair[0]);
        canonicalUuid(pair[1]);
      }
      if (generation != null) {
        decimal(generation, false);
      }
      decimal(sourceVersion, false);
      if ((checkpointStream == null) != (checkpointSequence == null)) {
        throw new IllegalArgumentException("Checkpoint fields must be applicable together");
      }
      if (checkpointStream != null) {
        text(checkpointStream);
        decimal(checkpointSequence, true);
      }
      evidence = bytes(evidence);
    }

    @Override
    public byte[] evidence() {
      return evidence.clone();
    }

    public String key() {
      return kind.name() + ":" + scopeId;
    }

    public byte[] canonicalBytes() {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      for (String value : List.of("account-draft-source-evidence/v1", kind.name(), scopeId)) {
        frame(output, value);
      }
      frame(output, generation == null ? "ABSENT" : "PRESENT");
      if (generation != null) {
        frame(output, generation);
      }
      frame(output, sourceVersion);
      frame(output, checkpointStream == null ? "ABSENT" : "PRESENT");
      if (checkpointStream != null) {
        frame(output, checkpointStream);
        frame(output, checkpointSequence);
      }
      frame(output, evidence);
      return output.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof SourceEvidence source
              && Arrays.equals(canonicalBytes(), source.canonicalBytes()));
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(canonicalBytes());
    }

    /** Decodes only the exact immutable framing written by this source owner. */
    public static SourceEvidence fromStored(byte[] stored) {
      FrameReader reader = new FrameReader(stored);
      reader.expect("account-draft-source-evidence/v1");
      SourceKind kind = SourceKind.valueOf(reader.text());
      String scope = reader.text();
      String generation = reader.optionalText();
      String version = reader.text();
      String stream = reader.optionalText();
      String sequence = stream == null ? null : reader.text();
      SourceEvidence source =
          new SourceEvidence(kind, scope, generation, version, stream, sequence, reader.bytes());
      reader.requireEnd();
      if (!Arrays.equals(stored, source.canonicalBytes())) {
        throw new IllegalArgumentException("Noncanonical stored source evidence");
      }
      return source;
    }
  }

  /** Bounded exact frame decoding for persisted source evidence and source-change intent. */
  public static final class FrameReader {
    private final ByteBuffer input;

    public FrameReader(byte[] stored) {
      input = ByteBuffer.wrap(DraftAuthorizationFenceBinding.bytes(stored));
    }

    public byte[] bytes() {
      if (input.remaining() < Integer.BYTES) {
        throw new IllegalArgumentException("Truncated immutable source frame");
      }
      int size = input.getInt();
      if (size < 0 || size > input.remaining()) {
        throw new IllegalArgumentException("Invalid immutable source frame size");
      }
      byte[] value = new byte[size];
      input.get(value);
      return value;
    }

    public String text() {
      byte[] value = bytes();
      String text = new String(value, StandardCharsets.UTF_8);
      DraftAuthorizationFenceBinding.text(text);
      if (!Arrays.equals(value, text.getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("Invalid UTF-8 source frame");
      }
      return text;
    }

    public String optionalText() {
      String presence = text();
      if ("ABSENT".equals(presence)) {
        return null;
      }
      if (!"PRESENT".equals(presence)) {
        throw new IllegalArgumentException("Invalid immutable source presence");
      }
      return text();
    }

    public void expect(String expected) {
      if (!expected.equals(text())) {
        throw new IllegalArgumentException("Unsupported immutable source schema");
      }
    }

    public int remaining() {
      return input.remaining();
    }

    public void requireEnd() {
      if (input.hasRemaining()) {
        throw new IllegalArgumentException("Trailing immutable source frames");
      }
    }
  }

  public enum Owner {
    GAME_DESIGN,
    WORLD,
    ENTITY,
    GAME_LOGIC,
    AUTOMATION
  }

  public enum Outcome {
    COMMITTED,
    DEFINITIVELY_ABORTED
  }

  private static final String OWNER_READBACK_SCHEMA_V1 = "account-draft-owner-readback/v1";
  private static final String OWNER_READBACK_SCHEMA_V2 = "account-draft-owner-readback/v2";

  /** Only a future authenticated owner verifier may supply this exact durable readback. */
  public record OwnerReadback(
      Owner owner,
      Outcome outcome,
      UUID operationId,
      UUID commitId,
      UUID fenceId,
      String inputDigest,
      byte[] fullBinding,
      byte[] result) {
    public OwnerReadback {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(outcome);
      requireUuid(operationId);
      requireUuid(commitId);
      requireUuid(fenceId);
      if (inputDigest == null || !inputDigest.matches("sha256:[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Canonical digest required");
      }
      fullBinding = bytes(fullBinding);
      result = bytes(result);
    }

    /** Reads only canonical original evidence; decoding does not authenticate its producer. */
    public static OwnerReadback fromStored(byte[] original) {
      byte[] stored = bytes(original);
      FrameReader reader = new FrameReader(stored);
      String schema = reader.text();
      if (!OWNER_READBACK_SCHEMA_V1.equals(schema) && !OWNER_READBACK_SCHEMA_V2.equals(schema)) {
        throw new IllegalArgumentException("Unsupported stored owner readback schema");
      }
      OwnerReadback readback =
          new OwnerReadback(
              Owner.valueOf(reader.text()),
              Outcome.valueOf(reader.text()),
              canonicalUuidValue(reader.text()),
              canonicalUuidValue(reader.text()),
              canonicalUuidValue(reader.text()),
              reader.text(),
              reader.bytes(),
              reader.bytes());
      reader.requireEnd();
      DraftAuthorizationFenceBinding binding =
          DraftAuthorizationFenceBinding.fromStored(readback.fullBinding());
      if (!schema.equals(readbackSchema(binding))) {
        throw new IllegalArgumentException("Owner readback schema differs from its full binding");
      }
      readback.requireBinding(binding);
      if (!Arrays.equals(stored, readback.canonicalBytes())) {
        throw new IllegalArgumentException("Noncanonical stored owner readback");
      }
      return readback;
    }

    @Override
    public byte[] fullBinding() {
      return fullBinding.clone();
    }

    @Override
    public byte[] result() {
      return result.clone();
    }

    public void requireBinding(DraftAuthorizationFenceBinding binding) {
      if (!operationId.equals(binding.operationId())
          || !commitId.equals(binding.commitId())
          || !fenceId.equals(binding.fenceId())
          || !inputDigest.equals(binding.inputDigest())
          || !Arrays.equals(fullBinding, binding.canonicalBytes())) {
        throw new IllegalArgumentException("Owner readback differs from complete Draft binding");
      }
      if (SCHEMA_V2.equals(binding.schemaVersion()) && !binding.requiredOwners().contains(owner)) {
        throw new IllegalArgumentException("Owner readback owner is not required by Draft binding");
      }
      if (SCHEMA_V1.equals(binding.schemaVersion()) && !V1_OWNERS.contains(owner)) {
        throw new IllegalArgumentException("V1 owner readback owner is outside its closed schema");
      }
    }

    public byte[] canonicalBytes() {
      DraftAuthorizationFenceBinding binding =
          DraftAuthorizationFenceBinding.fromStored(fullBinding);
      requireBinding(binding);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      for (String value :
          List.of(
              readbackSchema(binding),
              owner.name(),
              outcome.name(),
              operationId.toString(),
              commitId.toString(),
              fenceId.toString(),
              inputDigest)) {
        frame(output, value);
      }
      frame(output, fullBinding);
      frame(output, result);
      return output.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof OwnerReadback readback
              && Arrays.equals(canonicalBytes(), readback.canonicalBytes()));
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(canonicalBytes());
    }

    private static String readbackSchema(DraftAuthorizationFenceBinding binding) {
      return SCHEMA_V1.equals(binding.schemaVersion())
          ? OWNER_READBACK_SCHEMA_V1
          : OWNER_READBACK_SCHEMA_V2;
    }
  }

  public static byte[] bytes(byte[] value) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException("Exact bytes required");
    }
    return value.clone();
  }

  public static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Non-nil UUID required");
    }
  }

  public static void canonicalUuid(String value) {
    UUID id = UUID.fromString(value);
    requireUuid(id);
    if (!id.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical UUID required");
    }
  }

  static void text(String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Nonempty canonical text required");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
          throw new IllegalArgumentException("Unpaired surrogate");
        }
      } else if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException("Unpaired surrogate");
      }
    }
  }

  public static void decimal(String value, boolean zero) {
    if (value == null || !value.matches(zero ? "0|[1-9][0-9]*" : "[1-9][0-9]*")) {
      throw new IllegalArgumentException("Canonical decimal string required");
    }
  }

  public static void frame(ByteArrayOutputStream output, String value) {
    text(value);
    frame(output, value.getBytes(StandardCharsets.UTF_8));
  }

  public static void frame(ByteArrayOutputStream output, byte[] value) {
    try {
      DataOutputStream data = new DataOutputStream(output);
      data.writeInt(value.length);
      data.write(value);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID canonicalUuidValue(String value) {
    canonicalUuid(value);
    return UUID.fromString(value);
  }
}
