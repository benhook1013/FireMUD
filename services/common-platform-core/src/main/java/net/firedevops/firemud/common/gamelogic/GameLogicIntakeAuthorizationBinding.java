package net.firedevops.firemud.common.gamelogic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;

/** Distinct GL retention permission input. Only Account's genuine producer may establish HELD. */
public record GameLogicIntakeAuthorizationBinding(
    UUID operationId,
    UUID fenceId,
    UUID intakeRequestId,
    UUID actorAccountId,
    GameplayRuleSelectedSource source,
    List<SourceEvidence> sources) {
  public static final String SCHEMA = "account-game-logic-intake-authorization/v1";

  public GameLogicIntakeAuthorizationBinding {
    for (var id : List.of(operationId, fenceId, intakeRequestId, actorAccountId))
      DraftAuthorizationFenceBinding.requireUuid(id);
    Objects.requireNonNull(source);
    sources =
        List.copyOf(Objects.requireNonNull(sources)).stream()
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    if (sources.isEmpty()
        || sources.stream().map(SourceEvidence::key).distinct().count() != sources.size())
      throw new IllegalArgumentException("Complete distinct Account sources required");
    for (var value : sources) {
      String scope =
          switch (value.kind()) {
            case ACCOUNT, GLOBAL_ROLES -> actorAccountId.toString();
            case TENANT -> tenantId(source).toString();
            case MEMBERSHIP -> actorAccountId + "/" + tenantId(source);
            default -> null;
          };
      if (scope != null && !scope.equals(value.scopeId()))
        throw new IllegalArgumentException("Intake source differs from actor/tenant");
    }
  }

  private static UUID tenantId(GameplayRuleSelectedSource source) {
    return source.binding().target().canonicalTenantId();
  }

  @Override
  public List<SourceEvidence> sources() {
    return List.copyOf(sources);
  }

  public UUID tenantId() {
    return tenantId(source);
  }

  public UUID versionId() {
    return source.binding().target().canonicalVersionId();
  }

  public byte[] canonicalBytes() {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    for (var id : List.of(operationId, fenceId, intakeRequestId, actorAccountId))
      DraftAuthorizationFenceBinding.frame(out, id.toString());
    DraftAuthorizationFenceBinding.frame(out, source.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, Integer.toString(sources.size()));
    for (var value : sources) DraftAuthorizationFenceBinding.frame(out, value.canonicalBytes());
    if (out.size() > 4194304) throw new IllegalArgumentException("Intake order exceeds 4 MiB");
    return out.toByteArray();
  }

  public String digest() {
    return DraftAuthorizationFenceBinding.digest(canonicalBytes());
  }

  public static GameLogicIntakeAuthorizationBinding fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > 4194304)
      throw new IllegalArgumentException("Invalid original intake order size");
    var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
    reader.expect(SCHEMA);
    UUID operation = uuid(reader.text()),
        fence = uuid(reader.text()),
        request = uuid(reader.text()),
        actor = uuid(reader.text());
    var source = new GameplayRuleSelectedSource(new String(reader.bytes(), StandardCharsets.UTF_8));
    String countText = reader.text();
    DraftAuthorizationFenceBinding.decimal(countText, false);
    int count = Integer.parseInt(countText);
    if (count > reader.remaining() / Integer.BYTES)
      throw new IllegalArgumentException("Incomplete source vector");
    var sources = new java.util.ArrayList<SourceEvidence>();
    for (int i = 0; i < count; i++) sources.add(SourceEvidence.fromStored(reader.bytes()));
    reader.requireEnd();
    var result =
        new GameLogicIntakeAuthorizationBinding(operation, fence, request, actor, source, sources);
    if (!Arrays.equals(bytes, result.canonicalBytes()))
      throw new IllegalArgumentException("Noncanonical intake order");
    return result;
  }

  private static UUID uuid(String value) {
    DraftAuthorizationFenceBinding.canonicalUuid(value);
    return UUID.fromString(value);
  }
}
