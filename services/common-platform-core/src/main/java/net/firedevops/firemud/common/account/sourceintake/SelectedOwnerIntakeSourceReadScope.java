package net.firedevops.firemud.common.account.sourceintake;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Integrity input for a distinct preliminary Account source reservation. Decoding establishes no
 * Account authentication, source completeness, or finalized owner retention permission.
 */
public record SelectedOwnerIntakeSourceReadScope(
    Owner owner,
    String targetNamespace,
    UUID operationId,
    UUID fenceId,
    UUID intakeRequestId,
    UUID actorAccountId,
    DraftCommitBinding selected) {
  public static final int MAX_BYTES = 4194304;

  public SelectedOwnerIntakeSourceReadScope {
    schemaFor(owner);
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace))
      throw new IllegalArgumentException("Canonical scope namespace required");
    for (var id : List.of(operationId, fenceId, intakeRequestId, actorAccountId))
      DraftAuthorizationFenceBinding.requireUuid(id);
    Objects.requireNonNull(selected, "selected");
    encode(owner, targetNamespace, operationId, fenceId, intakeRequestId, actorAccountId, selected);
  }

  public String schema() {
    return schemaFor(owner);
  }

  public String purpose() {
    return purposeFor(owner);
  }

  public String intendedReader() {
    return accountReader(targetNamespace);
  }

  public byte[] canonicalBytes() {
    return encode(
        owner, targetNamespace, operationId, fenceId, intakeRequestId, actorAccountId, selected);
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  public static SelectedOwnerIntakeSourceReadScope fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("Invalid owner source scope size");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    String schema = reader.text();
    Owner owner = Owner.valueOf(reader.text());
    if (!schemaFor(owner).equals(schema))
      throw new IllegalArgumentException("Owner source scope schema differs");
    String namespace = reader.text();
    UUID operation = uuid(reader.text());
    UUID fence = uuid(reader.text());
    UUID request = uuid(reader.text());
    UUID actor = uuid(reader.text());
    var selected = DraftCommitBinding.fromStored(reader.text(), reader.text());
    var result =
        new SelectedOwnerIntakeSourceReadScope(
            owner, namespace, operation, fence, request, actor, selected);
    reader.expect(result.intendedReader());
    reader.expect(result.purpose());
    reader.requireEnd();
    if (!Arrays.equals(bytes, result.canonicalBytes()))
      throw new IllegalArgumentException("Noncanonical owner source scope");
    return result;
  }

  private static byte[] encode(
      Owner owner,
      String namespace,
      UUID operation,
      UUID fence,
      UUID request,
      UUID actor,
      DraftCommitBinding selected) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, schemaFor(owner));
    DraftAuthorizationFenceBinding.frame(out, owner.name());
    DraftAuthorizationFenceBinding.frame(out, namespace);
    for (var id : List.of(operation, fence, request, actor))
      DraftAuthorizationFenceBinding.frame(out, id.toString());
    DraftAuthorizationFenceBinding.frame(out, selected.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, selected.digest());
    DraftAuthorizationFenceBinding.frame(out, accountReader(namespace));
    DraftAuthorizationFenceBinding.frame(out, purposeFor(owner));
    if (out.size() > MAX_BYTES)
      throw new IllegalArgumentException("Owner source scope exceeds 4 MiB");
    return out.toByteArray();
  }

  private static String schemaFor(Owner owner) {
    if (owner == Owner.ENTITY_MANAGEMENT) return "account-entity-intake-source-read/v1";
    if (owner == Owner.AUTOMATION_SCRIPTING) return "account-automation-intake-source-read/v1";
    throw new IllegalArgumentException("Unsupported selected source intake owner");
  }

  private static String purposeFor(Owner owner) {
    schemaFor(owner);
    return owner == Owner.ENTITY_MANAGEMENT ? "ENTITY_INTAKE_SOURCE" : "AUTOMATION_INTAKE_SOURCE";
  }

  private static String accountReader(String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/account-service";
  }

  private static UUID uuid(String value) {
    DraftAuthorizationFenceBinding.canonicalUuid(value);
    return UUID.fromString(value);
  }
}
