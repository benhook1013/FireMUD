package net.firedevops.firemud.accountservice.tenantcreation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;

/**
 * Closed versionless CREATE_TENANT correlation. Supplied actor, original authorization capture and
 * source bytes require authenticated, current owner producers; storage cannot establish them. The
 * capture is immutable evidence for this fresh operation, not finalized participant outcomes. An
 * already-finalized creation authorization is provenance only and cannot authorize bootstrap. There
 * is no Draft, Version, World participant or reusable authorization in this family.
 */
public record TenantCreationAuthorizationFenceBinding(
    UUID operationId,
    UUID requestId,
    UUID fenceId,
    UUID actorAccountId,
    UUID tenantId,
    UUID creationOperationId,
    String targetNamespace,
    String sourceGameTenantKey,
    String name,
    String description,
    String creationRequestDigest,
    byte[] originalAuthorizationCapture,
    List<SourceEvidence> sources) {
  public static final String SCHEMA = "account-create-tenant-authorization-fence/v1";

  public TenantCreationAuthorizationFenceBinding {
    for (UUID id :
        List.of(operationId, requestId, fenceId, actorAccountId, tenantId, creationOperationId)) {
      requireUuid(id);
    }
    for (String value : List.of(targetNamespace, sourceGameTenantKey, name)) {
      text(value);
    }
    if (!targetNamespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
      throw new IllegalArgumentException("Canonical target namespace required");
    }
    if (description != null) {
      GameTenantCreationDigest.utf8ByteLength(description);
      if (description.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("NUL in creation description");
      }
    }
    if (sourceGameTenantKey.codePointCount(0, sourceGameTenantKey.length()) > 36
        || name.codePointCount(0, name.length()) > 100
        || (description != null && description.codePointCount(0, description.length()) > 255)
        || !GameTenantCreationDigest.requestDigest(
                targetNamespace, requestId, sourceGameTenantKey, name, description)
            .equals(creationRequestDigest)) {
      throw new IllegalArgumentException("Exact Game Design creation request digest required");
    }
    originalAuthorizationCapture = bytes(originalAuthorizationCapture);
    sources =
        Objects.requireNonNull(sources).stream()
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    if (sources.isEmpty()
        || sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw new IllegalArgumentException("Distinct applicable current Account sources required");
    }
  }

  @Override
  public byte[] originalAuthorizationCapture() {
    return originalAuthorizationCapture.clone();
  }

  @Override
  public List<SourceEvidence> sources() {
    return List.copyOf(sources);
  }

  public byte[] canonicalBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String value :
        List.of(
            SCHEMA,
            "CREATE_TENANT",
            operationId.toString(),
            requestId.toString(),
            fenceId.toString(),
            actorAccountId.toString(),
            tenantId.toString(),
            creationOperationId.toString(),
            targetNamespace,
            sourceGameTenantKey,
            name)) {
      frame(out, value);
    }
    frame(out, description == null ? "ABSENT" : "PRESENT");
    if (description != null) {
      frame(out, description);
    }
    frame(out, creationRequestDigest);
    frame(out, originalAuthorizationCapture);
    frame(out, Integer.toString(sources.size()));
    for (SourceEvidence source : sources) {
      frame(out, source.canonicalBytes());
    }
    // Exact participants from inception, with their distinct owner-local operation identities.
    frame(out, "GAME_DESIGN");
    frame(out, creationOperationId.toString());
    frame(out, "ACCOUNT");
    frame(out, operationId.toString());
    return out.toByteArray();
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  /** Recover only the fresh operation's original evidence, never finalized participant outcomes. */
  public static TenantCreationAuthorizationFenceBinding fromStored(byte[] stored) {
    Reader reader = new Reader(stored);
    reader.expect(SCHEMA);
    reader.expect("CREATE_TENANT");
    UUID operation = UUID.fromString(reader.text());
    UUID request = UUID.fromString(reader.text());
    UUID fence = UUID.fromString(reader.text());
    UUID actor = UUID.fromString(reader.text());
    UUID tenant = UUID.fromString(reader.text());
    UUID creation = UUID.fromString(reader.text());
    String namespace = reader.text();
    String key = reader.text();
    String name = reader.text();
    String presence = reader.text();
    if (!"ABSENT".equals(presence) && !"PRESENT".equals(presence)) {
      throw new IllegalArgumentException("Invalid creation description presence");
    }
    String description = "PRESENT".equals(presence) ? reader.text() : null;
    String digest = reader.text();
    byte[] authorization = reader.bytes();
    String count = reader.text();
    if (!count.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException("Canonical source count required");
    }
    int size;
    try {
      size = Integer.parseInt(count);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Source count out of range", invalid);
    }
    if (size > reader.input.remaining() / Integer.BYTES) {
      throw new IllegalArgumentException("Incomplete stored source vector");
    }
    List<SourceEvidence> sources = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      sources.add(SourceEvidence.fromStored(reader.bytes()));
    }
    reader.expect("GAME_DESIGN");
    reader.expect(creation.toString());
    reader.expect("ACCOUNT");
    reader.expect(operation.toString());
    var binding =
        new TenantCreationAuthorizationFenceBinding(
            operation,
            request,
            fence,
            actor,
            tenant,
            creation,
            namespace,
            key,
            name,
            description,
            digest,
            authorization,
            sources);
    if (reader.input.hasRemaining() || !Arrays.equals(stored, binding.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical stored CREATE_TENANT binding");
    }
    return binding;
  }

  public enum Owner {
    GAME_DESIGN,
    ACCOUNT
  }

  public enum Outcome {
    COMMITTED,
    DEFINITIVELY_ABORTED
  }

  /**
   * Definitive exact readback, never timeout/absence; future authenticated verifier supplies it.
   */
  public record OwnerReadback(
      Owner owner, Outcome outcome, UUID ownerOperationId, byte[] fullBinding, byte[] result) {
    public OwnerReadback {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(outcome);
      requireUuid(ownerOperationId);
      fullBinding = bytes(fullBinding);
      result = bytes(result);
    }

    @Override
    public byte[] fullBinding() {
      return fullBinding.clone();
    }

    @Override
    public byte[] result() {
      return result.clone();
    }

    public void requireBinding(TenantCreationAuthorizationFenceBinding binding) {
      UUID expected =
          owner == Owner.GAME_DESIGN ? binding.creationOperationId() : binding.operationId();
      if (!expected.equals(ownerOperationId)
          || !Arrays.equals(fullBinding, binding.canonicalBytes())) {
        throw new IllegalArgumentException(
            "Owner readback differs from exact CREATE_TENANT binding");
      }
    }

    public byte[] canonicalBytes() {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      for (String value :
          List.of(
              "account-create-tenant-owner-readback/v1",
              owner.name(),
              outcome.name(),
              ownerOperationId.toString())) {
        frame(out, value);
      }
      frame(out, fullBinding);
      frame(out, result);
      return out.toByteArray();
    }
  }

  private static void requireUuid(UUID id) {
    if (id == null || id.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Non-nil UUID required");
    }
  }

  private static void text(String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Nonempty canonical text required");
    }
    GameTenantCreationDigest.utf8ByteLength(value);
  }

  private static byte[] bytes(byte[] value) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException("Exact bytes required");
    }
    return value.clone();
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    frame(out, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    try {
      DataOutputStream data = new DataOutputStream(out);
      data.writeInt(value.length);
      data.write(value);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static final class Reader {
    private final ByteBuffer input;

    private Reader(byte[] stored) {
      input = ByteBuffer.wrap(TenantCreationAuthorizationFenceBinding.bytes(stored));
    }

    private byte[] bytes() {
      if (input.remaining() < Integer.BYTES) {
        throw new IllegalArgumentException("Truncated CREATE_TENANT frame");
      }
      int length = input.getInt();
      if (length < 0 || length > input.remaining()) {
        throw new IllegalArgumentException("Invalid CREATE_TENANT frame size");
      }
      byte[] value = new byte[length];
      input.get(value);
      return value;
    }

    private String text() {
      byte[] value = bytes();
      String decoded = new String(value, StandardCharsets.UTF_8);
      if (!Arrays.equals(value, decoded.getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("Invalid UTF-8 creation frame");
      }
      return decoded;
    }

    private void expect(String value) {
      if (!value.equals(text())) {
        throw new IllegalArgumentException("Unexpected closed CREATE_TENANT field");
      }
    }
  }
}
