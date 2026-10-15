package net.firedevops.firemud.common.account.sourceintake;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;

/**
 * Integrity input for Account's distinct selected-owner source-retention authorization. Decoding
 * proves only the canonical relationship among the supplied content and source evidence; it does
 * not authenticate Account, establish HELD/current authority, prove a complete source census, or
 * create an owner receipt.
 */
public record SelectedOwnerIntakeAuthorizationBinding(
    SelectedOwnerIntakeSourceContent content, List<SourceEvidence> sources) {
  public static final int MAX_BYTES = 16 * 1024 * 1024;

  public SelectedOwnerIntakeAuthorizationBinding {
    Objects.requireNonNull(content, "content");
    SelectedOwnerIntakeSourceReadScope scope = content.scope();
    schemaFor(scope.owner());
    sources =
        List.copyOf(Objects.requireNonNull(sources, "sources")).stream()
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    if (sources.isEmpty()
        || sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw new IllegalArgumentException("Complete distinct Account sources required");
    }
    for (SourceEvidence source : sources) requireActorTenantScope(source, scope);
    canonicalBytes(
        content,
        sources,
        intendedReader(scope.owner(), scope.targetNamespace()),
        purposeFor(scope.owner()));
  }

  @Override
  public List<SourceEvidence> sources() {
    return List.copyOf(sources);
  }

  public Owner owner() {
    return content.scope().owner();
  }

  public String targetNamespace() {
    return content.scope().targetNamespace();
  }

  public UUID operationId() {
    return content.scope().operationId();
  }

  public UUID fenceId() {
    return content.scope().fenceId();
  }

  public UUID intakeRequestId() {
    return content.scope().intakeRequestId();
  }

  public UUID actorAccountId() {
    return content.scope().actorAccountId();
  }

  public DraftCommitBinding selected() {
    return content.scope().selected();
  }

  public UUID tenantId() {
    return selected().target().canonicalTenantId();
  }

  public UUID versionId() {
    return selected().target().canonicalVersionId();
  }

  public String schema() {
    return schemaFor(owner());
  }

  public String purpose() {
    return purposeFor(owner());
  }

  public String intendedReader() {
    return intendedReader(owner(), targetNamespace());
  }

  public byte[] canonicalBytes() {
    return canonicalBytes(content, sources, intendedReader(), purpose());
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  /**
   * Decodes a self-contained integrity envelope by revalidating its embedded exact source scope,
   * complete six-family content and all Account source-evidence frames.
   */
  public static SelectedOwnerIntakeAuthorizationBinding fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Selected owner intake authorization size is invalid");
    }
    byte[] stored = bytes.clone();
    var reader = new DraftAuthorizationFenceBinding.FrameReader(stored);
    String schema = reader.text();
    Owner owner = ownerForSchema(schema);
    byte[] contentBytes = reader.bytes();
    String contentDigest = reader.text();
    if (!DraftAuthorizationFenceBinding.digest(contentBytes).equals(contentDigest)) {
      throw new IllegalArgumentException("Selected owner intake content digest differs");
    }

    SelectedOwnerIntakeSourceReadScope embeddedScope = embeddedScope(contentBytes);
    if (embeddedScope.owner() != owner) {
      throw new IllegalArgumentException("Selected owner intake schema differs from content owner");
    }
    SelectedOwnerIntakeSourceContent content =
        SelectedOwnerIntakeSourceContent.fromStored(contentBytes, embeddedScope, contentDigest);
    String recipientReader = reader.text();
    String purpose = reader.text();
    String expectedReader = intendedReader(owner, embeddedScope.targetNamespace());
    String expectedPurpose = purposeFor(owner);
    if (!expectedReader.equals(recipientReader) || !expectedPurpose.equals(purpose)) {
      throw new IllegalArgumentException("Selected owner intake recipient or purpose differs");
    }

    String countText = reader.text();
    DraftAuthorizationFenceBinding.decimal(countText, false);
    int count = Integer.parseInt(countText);
    if (count > reader.remaining() / Integer.BYTES) {
      throw new IllegalArgumentException("Incomplete selected owner intake source vector");
    }
    var sources = new java.util.ArrayList<SourceEvidence>();
    for (int i = 0; i < count; i++) {
      sources.add(SourceEvidence.fromStored(reader.bytes()));
    }
    reader.requireEnd();

    var result = new SelectedOwnerIntakeAuthorizationBinding(content, sources);
    if (!schema.equals(result.schema())
        || !recipientReader.equals(result.intendedReader())
        || !purpose.equals(result.purpose())
        || !Arrays.equals(stored, result.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical selected owner intake authorization");
    }
    return result;
  }

  private static SelectedOwnerIntakeSourceReadScope embeddedScope(byte[] contentBytes) {
    if (contentBytes == null
        || contentBytes.length == 0
        || contentBytes.length > SelectedOwnerIntakeSourceContent.MAX_BYTES) {
      throw new IllegalArgumentException("Selected owner intake content size is invalid");
    }
    var contentReader = new DraftAuthorizationFenceBinding.FrameReader(contentBytes);
    contentReader.expect(SelectedOwnerIntakeSourceContent.DOMAIN);
    byte[] scopeBytes = contentReader.bytes();
    String scopeDigest = contentReader.text();
    if (!DraftAuthorizationFenceBinding.digest(scopeBytes).equals(scopeDigest)) {
      throw new IllegalArgumentException("Selected owner intake scope digest differs");
    }
    return SelectedOwnerIntakeSourceReadScope.fromStored(scopeBytes);
  }

  private static byte[] canonicalBytes(
      SelectedOwnerIntakeSourceContent content,
      List<SourceEvidence> sources,
      String intendedReader,
      String purpose) {
    var out = new ByteArrayOutputStream();
    frameBounded(out, schemaFor(content.scope().owner()));
    byte[] contentBytes = content.canonicalBytes();
    frameBounded(out, contentBytes);
    frameBounded(out, content.digest());
    frameBounded(out, intendedReader);
    frameBounded(out, purpose);
    frameBounded(out, Integer.toString(sources.size()));
    for (SourceEvidence source : sources) {
      frameBounded(out, source.canonicalBytes());
    }
    return out.toByteArray();
  }

  private static void frameBounded(ByteArrayOutputStream out, String value) {
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    requireFrameCapacity(out, utf8.length);
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frameBounded(ByteArrayOutputStream out, byte[] value) {
    requireFrameCapacity(out, value.length);
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void requireFrameCapacity(ByteArrayOutputStream out, int frameBytes) {
    if ((long) out.size() + Integer.BYTES + frameBytes > MAX_BYTES) {
      throw new IllegalArgumentException("Selected owner intake authorization exceeds 16 MiB");
    }
  }

  private static void requireActorTenantScope(
      SourceEvidence source, SelectedOwnerIntakeSourceReadScope scope) {
    String expected =
        switch (source.kind()) {
          case ACCOUNT, GLOBAL_ROLES -> scope.actorAccountId().toString();
          case TENANT -> scope.selected().target().canonicalTenantId().toString();
          case MEMBERSHIP ->
              scope.actorAccountId() + "/" + scope.selected().target().canonicalTenantId();
          default -> null;
        };
    if (expected != null && !expected.equals(source.scopeId())) {
      throw new IllegalArgumentException("Intake source differs from actor/tenant");
    }
  }

  private static String schemaFor(Owner owner) {
    if (owner == Owner.ENTITY_MANAGEMENT) return "account-entity-intake-authorization/v1";
    if (owner == Owner.AUTOMATION_SCRIPTING) return "account-automation-intake-authorization/v1";
    throw new IllegalArgumentException("Unsupported selected source intake owner");
  }

  private static Owner ownerForSchema(String schema) {
    if ("account-entity-intake-authorization/v1".equals(schema)) return Owner.ENTITY_MANAGEMENT;
    if ("account-automation-intake-authorization/v1".equals(schema))
      return Owner.AUTOMATION_SCRIPTING;
    throw new IllegalArgumentException("Unsupported selected owner intake authorization schema");
  }

  private static String purposeFor(Owner owner) {
    schemaFor(owner);
    return owner == Owner.ENTITY_MANAGEMENT
        ? "ENTITY_INTAKE_RETENTION"
        : "AUTOMATION_INTAKE_RETENTION";
  }

  private static String intendedReader(Owner owner, String namespace) {
    String workload =
        switch (owner) {
          case ENTITY_MANAGEMENT -> "entity-management-service";
          case AUTOMATION_SCRIPTING -> "automation-scripting-service";
          default -> throw new IllegalArgumentException("Unsupported selected source intake owner");
        };
    return "spiffe://firemud/ns/" + namespace + "/sa/" + workload;
  }
}
