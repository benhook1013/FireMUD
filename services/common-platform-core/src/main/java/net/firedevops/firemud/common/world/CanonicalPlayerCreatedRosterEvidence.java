package net.firedevops.firemud.common.world;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.common.publication.PlayerCreationDescriptor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActorKind;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterSnapshotReference;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Pure validation and digest functions for the PLAYER_CREATED roster wire values. */
public final class CanonicalPlayerCreatedRosterEvidence {
  public static final int MAX_REQUEST_BYTES = 16 * 1024;
  public static final int MAX_SOURCE_IDENTITY_BYTES = 4096;
  public static final int MAX_ROSTER_RECEIPT_BYTES = 66 * 1024;
  public static final int MAX_TERMINAL_BYTES = 192 * 1024 * 1024;
  public static final int MAX_SELECTED_RECEIPT_BYTES = 193 * 1024 * 1024;

  private static final int MAX_ORIGINAL_REQUEST_BYTES = 16 * 1024;
  private static final int MAX_ACCOUNT_ORIGINAL_RESPONSE_BYTES = 36 * 1024 * 1024;
  private static final int MAX_SOURCE_READBACK_BYTES = 32 * 1024 * 1024;
  private static final int MAX_ACCOUNT_PROTECTION_BYTES = 40 * 1024 * 1024;
  private static final int MAX_GAME_SESSION_PROTECTION_BYTES = 48 * 1024 * 1024;
  private static final int MAX_WORLD_PROTECTION_BYTES = 48 * 1024 * 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final String SNAPSHOT_DOMAIN = "firemud.entity.player-created-roster.v1";

  private CanonicalPlayerCreatedRosterEvidence() {}

  public static CanonicalPlayerCreatedRosterSourceIdentity requireSourceIdentity(
      CanonicalPlayerCreatedRosterSourceIdentity source) {
    Objects.requireNonNull(source, "source");
    if (source.getSerializedSize() > MAX_SOURCE_IDENTITY_BYTES
        || !source.getUnknownFields().asMap().isEmpty()
        || source.getSchemaVersion() != 1
        || !SHA256.matcher(source.getDescriptorDigest()).matches()
        || !SHA256.matcher(source.getAuthoredBindingDigest()).matches()) {
      throw new IllegalArgumentException("Closed bounded PLAYER_CREATED source identity required");
    }
    uuid(source.getDescriptorUuid());
    uuid(source.getDescriptorRevisionUuid());
    uuid(source.getAuthoredBindingUuid());
    return source;
  }

  public static CanonicalPlayerCreatedRosterRequest requireRequest(
      CanonicalPlayerCreatedRosterRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedTarget()
        || !request.hasPlayerExecutionContext()
        || !request.hasExpectedSource()) {
      throw new IllegalArgumentException("Closed bounded PLAYER_CREATED roster request required");
    }
    uuid(request.getRequestUuid());
    uuid(request.getCanonicalAccountUuid());
    CanonicalGameplayRosterTarget target = requireTarget(request.getExpectedTarget());
    requireSourceIdentity(request.getExpectedSource());
    requireContext(
        request.getPlayerExecutionContext(),
        request.getRequestUuid(),
        request.getCanonicalAccountUuid(),
        target,
        null);
    return request;
  }

  public static CanonicalPlayerCreatedRosterResponse requireResponse(
      CanonicalPlayerCreatedRosterRequest expected, CanonicalPlayerCreatedRosterResponse response) {
    requireRequest(expected);
    Objects.requireNonNull(response, "response");
    if (response.getSerializedSize() > MAX_ROSTER_RECEIPT_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasRoster()
        || !response.hasSourceIdentity()) {
      throw new IllegalArgumentException("Complete bounded PLAYER_CREATED roster receipt required");
    }
    CanonicalPlayerCreatedRosterSourceIdentity source =
        requireSourceIdentity(response.getSourceIdentity());
    if (!expected.getExpectedSource().equals(source)) {
      throw new IllegalArgumentException("PLAYER_CREATED roster source identity changed");
    }
    CanonicalGameplayRosterResponse roster = response.getRoster();
    if (roster.getSerializedSize() > MAX_ROSTER_RECEIPT_BYTES
        || !roster.getUnknownFields().asMap().isEmpty()
        || !roster.getCanonicalAccountUuid().equals(expected.getCanonicalAccountUuid())
        || !roster.hasTarget()
        || !roster.getTarget().equals(expected.getExpectedTarget())
        || !DIGEST.matcher(roster.getSnapshotDigest()).matches()
        || roster.getActorsCount() > 100) {
      throw new IllegalArgumentException("PLAYER_CREATED roster binding or bounds are invalid");
    }
    uuid(roster.getSnapshotUuid());
    Set<UUID> actors = new HashSet<>();
    for (CanonicalGameplayRosterActor actor : roster.getActorsList()) {
      if (!actor.getUnknownFields().asMap().isEmpty()
          || !actors.add(uuid(actor.getCharacterUuid()))
          || actor.getActorKind()
              != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER
          || !validDisplayName(actor.getDisplayName())) {
        throw new IllegalArgumentException("Closed unique PLAYER actor roster required");
      }
    }
    String recomputed =
        computeSnapshotDigest(
            expected.getCanonicalAccountUuid(),
            expected.getExpectedTarget(),
            source,
            roster.getActorsList());
    if (!recomputed.equals(roster.getSnapshotDigest())) {
      throw new IllegalArgumentException("PLAYER_CREATED roster snapshot digest changed");
    }
    return response;
  }

  public static CanonicalSelectedPlayerCreatedCharacterRequest requireSelectedRequest(
      CanonicalSelectedPlayerCreatedCharacterRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || !request.hasExpectedTarget()
        || !request.hasExpectedSnapshot()
        || !request.hasPlayerExecutionContext()
        || !request.hasExpectedSource()) {
      throw new IllegalArgumentException("Closed bounded selected PLAYER_CREATED request required");
    }
    uuid(request.getRequestUuid());
    uuid(request.getCanonicalAccountUuid());
    UUID selected = uuid(request.getSelectedCharacterUuid());
    CanonicalGameplayRosterTarget target = requireTarget(request.getExpectedTarget());
    requireSourceIdentity(request.getExpectedSource());
    if (!request.getExpectedSnapshot().getUnknownFields().asMap().isEmpty()
        || !DIGEST.matcher(request.getExpectedSnapshot().getSnapshotDigest()).matches()) {
      throw new IllegalArgumentException("Exact canonical roster snapshot reference required");
    }
    uuid(request.getExpectedSnapshot().getSnapshotUuid());
    requireContext(
        request.getPlayerExecutionContext(),
        request.getRequestUuid(),
        request.getCanonicalAccountUuid(),
        target,
        selected);
    return request;
  }

  /**
   * Validates pure selected-created wire values and bindings; this does not authenticate owners or
   * establish creation authority.
   */
  public static CanonicalSelectedPlayerCreatedCharacterResponse requireSelectedResponse(
      CanonicalSelectedPlayerCreatedCharacterRequest expected,
      CanonicalSelectedPlayerCreatedCharacterResponse response) {
    requireSelectedRequest(expected);
    Objects.requireNonNull(response, "response");
    if (response.getSerializedSize() > MAX_SELECTED_RECEIPT_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasTarget()
        || !response.hasSnapshot()
        || !response.hasSourceIdentity()
        || !response.hasTerminal()
        || !response.getRequestUuid().equals(expected.getRequestUuid())
        || !response.getCanonicalAccountUuid().equals(expected.getCanonicalAccountUuid())
        || !response.getSelectedCharacterUuid().equals(expected.getSelectedCharacterUuid())) {
      throw new IllegalArgumentException(
          "Complete bounded selected PLAYER_CREATED receipt required");
    }
    CanonicalGameplayRosterTarget target = requireTarget(response.getTarget());
    CanonicalPlayerCreatedRosterSourceIdentity source =
        requireSourceIdentity(response.getSourceIdentity());
    CanonicalGameplayRosterSnapshotReference snapshot = response.getSnapshot();
    if (!target.equals(expected.getExpectedTarget())
        || !snapshot.equals(expected.getExpectedSnapshot())
        || !source.equals(expected.getExpectedSource())) {
      throw new IllegalArgumentException("Selected PLAYER_CREATED receipt binding changed");
    }

    uuid(response.getCreationOperationUuid());
    if (!DIGEST.matcher(response.getMutationDigest()).matches()) {
      throw new IllegalArgumentException("Canonical selected creation mutation digest required");
    }

    EntityCharacterCreateTerminal terminal = response.getTerminal();
    if (terminal.getSerializedSize() > MAX_TERMINAL_BYTES
        || !terminal.getUnknownFields().asMap().isEmpty()
        || !terminal.hasOriginalRequest()
        || !terminal.hasAccountOriginal()
        || terminal.getOriginalAccountProtection().isEmpty()
        || terminal.getOriginalAccountProtection().size() > MAX_ACCOUNT_PROTECTION_BYTES
        || terminal.getOriginalGameSessionProtection().isEmpty()
        || terminal.getOriginalGameSessionProtection().size() > MAX_GAME_SESSION_PROTECTION_BYTES
        || terminal.getOriginalWorldProtection().isEmpty()
        || terminal.getOriginalWorldProtection().size() > MAX_WORLD_PROTECTION_BYTES
        || terminal.getOutcome()
            != EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED
        || !terminal.getCharacterId().equals(expected.getSelectedCharacterUuid())
        || terminal.getTerminalFence() != 1L
        || !terminal.getAbortRequestId().isEmpty()) {
      throw new IllegalArgumentException("Complete committed selected creation terminal required");
    }
    uuid(terminal.getTerminalId());
    canonicalInstant(terminal.getCompletedAt());
    uuid(terminal.getEntityIntakeRequestId());
    if (!SHA256.matcher(terminal.getEntitySourceReceiptDigest()).matches()) {
      throw new IllegalArgumentException("Canonical Entity source receipt digest required");
    }
    requireOriginalCommit(terminal.getOriginalRequest());
    ReadOriginalCharacterCreateResponse accountOriginal = terminal.getAccountOriginal();
    requireAccountOriginal(
        accountOriginal,
        terminal.getOriginalRequest().getOriginal(),
        expected.getCanonicalAccountUuid(),
        response.getCreationOperationUuid(),
        response.getMutationDigest());

    byte[] originalActionBytes = accountOriginal.getOriginalAction().toByteArray();
    CharacterCreateOperatorAction action =
        CharacterCreateOperatorActionCodec.decode(originalActionBytes);
    if (!Arrays.equals(originalActionBytes, CharacterCreateOperatorActionCodec.encode(action))
        || !response
            .getMutationDigest()
            .equals(CharacterCreateOperatorActionCodec.mutationDigest(action))
        || !action
            .scope()
            .targetNamespace()
            .equals(terminal.getOriginalRequest().getOriginal().getTargetNamespace())) {
      throw new IllegalArgumentException("Original CHARACTER_CREATE action is not canonical");
    }
    requireCreatedActionBinding(action, target, source, expected.getCanonicalAccountUuid());
    return response;
  }

  private static void requireOriginalCommit(CommitProtectedCharacterCreateRequest request) {
    if (!request.getUnknownFields().asMap().isEmpty()
        || request.getSerializedSize() > MAX_ORIGINAL_REQUEST_BYTES
        || !request.hasOriginal()
        || !request.hasAccountProtection()
        || !request.hasGameSessionProtection()
        || !request.hasWorldProtection()) {
      throw new IllegalArgumentException("Complete bounded original creation references required");
    }
    var original = request.getOriginal();
    if (!original.getUnknownFields().asMap().isEmpty()
        || original.getSerializedSize() > MAX_ORIGINAL_REQUEST_BYTES
        || !net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(
            original.getTargetNamespace())) {
      throw new IllegalArgumentException("Canonical original creation request required");
    }
    uuid(original.getOperationId());
    if (!DIGEST.matcher(original.getMutationDigest()).matches()) {
      throw new IllegalArgumentException("Canonical original creation digest required");
    }
    requireProtectionReference(request.getAccountProtection());
    requireProtectionReference(request.getGameSessionProtection());
    requireProtectionReference(request.getWorldProtection());
  }

  private static void requireProtectionReference(CharacterCreateProtectionReference reference) {
    if (!reference.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException("Closed original creation protection reference required");
    }
    uuid(reference.getProtectionId());
    if (reference.getProtectionFence() <= 0L) {
      throw new IllegalArgumentException("Positive original creation protection fence required");
    }
  }

  private static void requireAccountOriginal(
      ReadOriginalCharacterCreateResponse original,
      net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest request,
      String expectedAccount,
      String expectedOperation,
      String expectedDigest) {
    if (!original.getUnknownFields().asMap().isEmpty()
        || original.getSerializedSize() > MAX_ACCOUNT_ORIGINAL_RESPONSE_BYTES
        || original.getOriginalAction().isEmpty()
        || original.getOriginalAction().size() > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES
        || original.getOriginalSourceReadback().isEmpty()
        || original.getOriginalSourceReadback().size() > MAX_SOURCE_READBACK_BYTES
        || !original.getTargetNamespace().equals(request.getTargetNamespace())
        || !original.getOperationId().equals(expectedOperation)
        || !original.getMutationDigest().equals(expectedDigest)
        || !"CHARACTER_CREATE".equals(original.getPurpose())
        || !original.getAuthenticatedAccountId().equals(expectedAccount)) {
      throw new IllegalArgumentException("Exact bounded Account creation source required");
    }
    uuid(original.getCreateCharacterRequestId());
    requireConnectScope(original.getConnectScopeId());
    if (!request.getOperationId().equals(original.getOperationId())
        || !request.getMutationDigest().equals(original.getMutationDigest())) {
      throw new IllegalArgumentException("Account source differs from original request identity");
    }
  }

  private static void requireConnectScope(String value) {
    if (value.isBlank()
        || value.length() > 2048
        || value.indexOf(0) >= 0
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
      throw new IllegalArgumentException("Bounded original creation connect scope required");
    }
  }

  private static void requireCreatedActionBinding(
      CharacterCreateOperatorAction action,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      String accountUuid) {
    var scope = action.scope();
    var actionTarget = action.target();
    var mutation = action.mutation();
    var catalog = mutation.catalog();
    var authored = mutation.authoredSource();
    if (!scope.tenantId().equals(target.getTenantUuid())
        || !scope.playableStateNamespaceId().equals(target.getPlayableStateNamespaceUuid())
        || scope.playableStateScope()
            != CharacterCreateOperatorAction.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || !actionTarget.accountId().equals(accountUuid)
        || !actionTarget.realmId().equals(target.getRealmUuid())
        || !actionTarget.worldSlug().equals(target.getWorldSlug())
        || !actionTarget.realmSlug().equals(target.getRealmSlug())
        || !actionTarget.versionId().equals(target.getCanonicalVersionUuid())
        || !authored.canonicalTenantId().equals(target.getTenantUuid())
        || !authored.canonicalVersionId().equals(target.getCanonicalVersionUuid())
        || !authored.worldSlug().equals(target.getWorldSlug())
        || !catalog.tenantId().equals(target.getTenantUuid())
        || !catalog.realmId().equals(target.getRealmUuid())
        || !catalog.worldSlug().equals(target.getWorldSlug())
        || !catalog.realmSlug().equals(target.getRealmSlug())
        || !catalog.playableStateNamespaceId().equals(target.getPlayableStateNamespaceUuid())
        || !"SHARED".equals(catalog.stateScope())
        || !"PLAYER_CREATED".equals(catalog.characterCreationPolicy())
        || !authored.bindingId().equals(source.getAuthoredBindingUuid())
        || !authored.bindingDigest().equals(source.getAuthoredBindingDigest())) {
      throw new IllegalArgumentException(
          "Original creation durable target or authored binding changed");
    }
    PlayerCreationDescriptor descriptor =
        PlayerCreationDescriptor.parse(authored.descriptor().canonicalJson());
    if (!descriptor.descriptorId().equals(source.getDescriptorUuid())
        || !descriptor.descriptorRevisionId().equals(source.getDescriptorRevisionUuid())
        || !descriptor.digest().equals(source.getDescriptorDigest())
        || !authored.descriptor().digest().equals(source.getDescriptorDigest())) {
      throw new IllegalArgumentException("Original creation descriptor identity changed");
    }
  }

  private static void canonicalInstant(String value) {
    try {
      if (!java.time.Instant.parse(value).toString().equals(value)) {
        throw new IllegalArgumentException("Canonical creation terminal timestamp required");
      }
    } catch (java.time.DateTimeException malformed) {
      throw new IllegalArgumentException(
          "Canonical creation terminal timestamp required", malformed);
    }
  }

  public static String computeSnapshotDigest(
      String account,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      List<CanonicalGameplayRosterActor> orderedActors) {
    uuid(account);
    CanonicalGameplayRosterTarget canonicalTarget = requireTarget(target);
    CanonicalPlayerCreatedRosterSourceIdentity canonicalSource = requireSourceIdentity(source);
    Objects.requireNonNull(orderedActors, "orderedActors");
    if (orderedActors.size() > 100) {
      throw new IllegalArgumentException("PLAYER_CREATED roster exceeds 100 actors");
    }
    Set<UUID> actors = new HashSet<>();
    for (CanonicalGameplayRosterActor actor : orderedActors) {
      Objects.requireNonNull(actor, "actor");
      if (!actor.getUnknownFields().asMap().isEmpty()
          || !actors.add(uuid(actor.getCharacterUuid()))
          || actor.getActorKind()
              != CanonicalGameplayRosterActorKind.CANONICAL_ROSTER_ACTOR_KIND_PLAYER
          || !validDisplayName(actor.getDisplayName())) {
        throw new IllegalArgumentException("Closed unique PLAYER actor roster required");
      }
    }
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      writeUtf8(output, SNAPSHOT_DOMAIN);
      writeUtf8(output, account);
      writeBytes(output, canonicalTarget.toByteArray());
      writeBytes(output, canonicalSource.toByteArray());
      output.writeInt(orderedActors.size());
      for (CanonicalGameplayRosterActor actor : orderedActors) {
        writeUtf8(output, actor.getCharacterUuid());
        writeUtf8(output, actor.getDisplayName());
      }
      output.flush();
      return java.util.HexFormat.of().formatHex(sha256(bytes.toByteArray()));
    } catch (IOException impossible) {
      throw new IllegalStateException("In-memory roster digest encoding failed", impossible);
    }
  }

  private static CanonicalGameplayRosterTarget requireTarget(CanonicalGameplayRosterTarget target) {
    Objects.requireNonNull(target, "target");
    if (!target.getUnknownFields().asMap().isEmpty()
        || target.getCatalogRevision() <= 0
        || target.getPointerVersion() <= 0
        || target.getActiveWorldEpoch() <= 0
        || target.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || target.getEntryPolicy()
            != CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PLAYER_CREATED
        || !SLUG.matcher(target.getWorldSlug()).matches()
        || !SLUG.matcher(target.getRealmSlug()).matches()
        || !DIGEST.matcher(target.getPublishedPolicyDigest()).matches()
        || !DIGEST.matcher(target.getAdmissionPointerSnapshotDigest()).matches()
        || !DIGEST.matcher(target.getPublishedOwnerProofDigest()).matches()
        || target.getPublishedReleaseBundleRef().isBlank()
        || target.getPublishedReleaseBundleRef().length() > 1024
        || !target
            .getPublishedReleaseBundleRef()
            .equals(target.getPublishedReleaseBundleRef().trim())
        || target.getPublishedReleaseBundleRef().codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Complete SHARED PLAYER_CREATED target required");
    }
    uuid(target.getTenantUuid());
    uuid(target.getRealmUuid());
    uuid(target.getGameInstanceUuid());
    uuid(target.getCanonicalVersionUuid());
    uuid(target.getPlayableStateNamespaceUuid());
    return target;
  }

  private static void requireContext(
      PlayerExecutionContext context,
      String requestUuid,
      String accountUuid,
      CanonicalGameplayRosterTarget target,
      UUID selectedCharacter) {
    if (!context.getUnknownFields().asMap().isEmpty()
        || !context.getAccountId().equals(accountUuid)
        || !context.getRequestId().equals(requestUuid)
        || !context.getTenantId().equals(target.getTenantUuid())
        || !context.getRealmId().equals(target.getRealmUuid())
        || !context.getPlayableStateNamespaceId().equals(target.getPlayableStateNamespaceUuid())
        || !context.getGameInstanceId().equals(target.getGameInstanceUuid())
        || !context.getPlayableStateScope().equals("SHARED")
        || (selectedCharacter == null && !context.getCharacterId().isEmpty())
        || (selectedCharacter != null
            && !context.getCharacterId().equals(selectedCharacter.toString()))) {
      throw new IllegalArgumentException(
          "Player execution context does not match exact request target");
    }
    uuid(context.getAccountId());
    uuid(context.getRequestId());
    uuid(context.getTenantId());
    uuid(context.getRealmId());
    uuid(context.getPlayableStateNamespaceId());
    uuid(context.getGameInstanceId());
    uuid(context.getSessionId());
    if (selectedCharacter != null) uuid(context.getCharacterId());
  }

  private static UUID uuid(String value) {
    Objects.requireNonNull(value, "uuid");
    if (!UUID_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("Canonical non-nil UUID required");
    }
    UUID parsed = UUID.fromString(value);
    if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical non-nil UUID required");
    }
    return parsed;
  }

  private static boolean validDisplayName(String value) {
    if (value.isBlank()
        || !value.equals(value.trim())
        || value.codePointCount(0, value.length()) > 100
        || value.codePoints().anyMatch(Character::isISOControl)) {
      return false;
    }
    try {
      StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(java.nio.CharBuffer.wrap(value));
      return true;
    } catch (CharacterCodingException malformed) {
      return false;
    }
  }

  private static void writeUtf8(DataOutputStream output, String value) throws IOException {
    ByteBuffer encoded =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    writeBytes(output, bytes);
  }

  private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }
}
