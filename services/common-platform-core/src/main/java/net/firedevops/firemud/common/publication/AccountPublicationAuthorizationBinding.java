package net.firedevops.firemud.common.publication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.FrameReader;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;

/**
 * Distinct publication operation's immutable original input and Account source participation. This
 * validates complete selected Draft structure, not actor authentication or positive source
 * authority. The future Account producer must independently establish the complete current source
 * vector, including party and operative hosted terms. Draft COMMIT_ORDER and creation results never
 * authorize or settle this operation. Later World captures and release/artifact results remain
 * separate and must never rewrite the original selection digest.
 */
public record AccountPublicationAuthorizationBinding(
    UUID operationId, UUID fenceId, PreallocationInput input, List<SourceEvidence> sources) {
  public static final String SCHEMA = "account-publication-authorization/v1";

  public AccountPublicationAuthorizationBinding {
    DraftAuthorizationFenceBinding.requireUuid(operationId);
    DraftAuthorizationFenceBinding.requireUuid(fenceId);
    Objects.requireNonNull(input);
    sources =
        List.copyOf(Objects.requireNonNull(sources)).stream()
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    if (sources.isEmpty()
        || sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw new IllegalArgumentException("Distinct applicable publication sources required");
    }
    for (SourceEvidence source : sources) {
      String requiredScope =
          switch (source.kind()) {
            case ACCOUNT, GLOBAL_ROLES -> input.actorAccountId().toString();
            case TENANT -> input.selection().intent().canonicalTenantId().toString();
            case MEMBERSHIP ->
                input.actorAccountId() + "/" + input.selection().intent().canonicalTenantId();
            default -> null;
          };
      if (requiredScope != null && !requiredScope.equals(source.scopeId())) {
        throw new IllegalArgumentException(
            "Publication source differs from exact actor/tenant scope");
      }
    }
  }

  /** Stable caller/selection inputs, before any Account operation/fence or World allocation. */
  public record PreallocationInput(
      UUID actorAccountId, AuthoredDraftPublishSelectionBinding selection) {
    public PreallocationInput {
      DraftAuthorizationFenceBinding.requireUuid(actorAccountId);
      Objects.requireNonNull(selection);
      // Reconstruct the whole closed selection, including target, commit and synchronized fence.
      selection =
          AuthoredDraftPublishSelectionBinding.fromStored(
              selection.canonicalJson(), selection.digest());
    }

    public byte[] canonicalBytes() {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, "account-publication-input/v1");
      DraftAuthorizationFenceBinding.frame(out, actorAccountId.toString());
      DraftAuthorizationFenceBinding.frame(out, selection.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, selection.digest());
      return out.toByteArray();
    }

    public String digest() {
      return DraftAuthorizationFenceBinding.digest(canonicalBytes());
    }
  }

  public UUID tenantId() {
    return input.selection().intent().canonicalTenantId();
  }

  public String publishRequestId() {
    return input.selection().intent().publishRequestId();
  }

  @Override
  public List<SourceEvidence> sources() {
    return List.copyOf(sources);
  }

  public byte[] canonicalBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, operationId.toString());
    DraftAuthorizationFenceBinding.frame(out, fenceId.toString());
    DraftAuthorizationFenceBinding.frame(out, input.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, Integer.toString(sources.size()));
    for (SourceEvidence source : sources) {
      DraftAuthorizationFenceBinding.frame(out, source.canonicalBytes());
    }
    return out.toByteArray();
  }

  public static AccountPublicationAuthorizationBinding fromStored(byte[] stored) {
    FrameReader reader = new FrameReader(stored);
    reader.expect(SCHEMA);
    UUID operationId = uuid(reader.text());
    UUID fenceId = uuid(reader.text());
    FrameReader inputReader = new FrameReader(reader.bytes());
    inputReader.expect("account-publication-input/v1");
    UUID actor = uuid(inputReader.text());
    byte[] selectionBytes = inputReader.bytes();
    String digest = inputReader.text();
    inputReader.requireEnd();
    var selection =
        AuthoredDraftPublishSelectionBinding.fromStored(
            new String(selectionBytes, StandardCharsets.UTF_8), digest);
    String countText = reader.text();
    DraftAuthorizationFenceBinding.decimal(countText, false);
    final int count;
    try {
      count = Integer.parseInt(countText);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Publication source count out of range", invalid);
    }
    if (count > reader.remaining() / Integer.BYTES) {
      throw new IllegalArgumentException("Incomplete publication source vector");
    }
    List<SourceEvidence> sources = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      sources.add(SourceEvidence.fromStored(reader.bytes()));
    }
    reader.requireEnd();
    var binding =
        new AccountPublicationAuthorizationBinding(
            operationId, fenceId, new PreallocationInput(actor, selection), sources);
    if (!Arrays.equals(stored, binding.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical publication authorization binding");
    }
    return binding;
  }

  private static UUID uuid(String value) {
    DraftAuthorizationFenceBinding.canonicalUuid(value);
    return UUID.fromString(value);
  }
}
