package net.firedevops.firemud.entitymanagement.charactercreate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.client.AccountCharacterCreateSourceClient;
import net.firedevops.firemud.entitymanagement.client.WorldCharacterCreateLifecycleClient;
import net.firedevops.firemud.entitymanagement.v1.AbortProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateTerminal;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityCharacterCreateTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadOriginalCharacterCreateLifecycleProtectionRequest;

/**
 * Sole Entity allocator and definitive terminal owner. All owner calls finish before the local
 * zero-roster/actor/association/terminal transaction; no protection is released here.
 */
public final class EntityCharacterCreateService {
  private final String namespace;
  private final AccountCharacterCreateSourceClient account;
  private final GameSessionCharacterCreateProtectionReader gameSession;
  private final WorldCharacterCreateLifecycleClient world;
  private final EntityCharacterCreateSource source;
  private final EntityCharacterCreateRepository repository;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repositories are managed dependencies kept internal.")
  public EntityCharacterCreateService(
      String namespace,
      AccountCharacterCreateSourceClient account,
      GameSessionCharacterCreateProtectionReader gameSession,
      WorldCharacterCreateLifecycleClient world,
      EntityCharacterCreateSource source,
      EntityCharacterCreateRepository repository) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical namespace required");
    this.namespace = namespace;
    this.account = Objects.requireNonNull(account);
    this.gameSession = Objects.requireNonNull(gameSession);
    this.world = Objects.requireNonNull(world);
    this.source = Objects.requireNonNull(source);
    this.repository = Objects.requireNonNull(repository);
  }

  public EntityCharacterCreateTerminal commit(CommitProtectedCharacterCreateRequest request) {
    requireMutation(request);
    var existing = repository.replay(request);
    if (existing.isPresent()) return existing.orElseThrow();
    return repository.settle(readAllOwners(request), null);
  }

  public EntityCharacterCreateTerminal abort(AbortProtectedCharacterCreateRequest request) {
    EntityCharacterCreatePeerInterceptor.requirePeer(namespace, true);
    EntityCharacterCreateBindings.exact(request);
    if (!request.hasCreation()) throw EntityCharacterCreateBindings.invalid();
    EntityCharacterCreateBindings.id(request.getAbortRequestId());
    requireMutation(request.getCreation());
    var existing = repository.replay(request.getCreation());
    var terminal =
        existing.orElseGet(
            () ->
                repository.settle(
                    readAllOwners(request.getCreation()), request.getAbortRequestId()));
    if (terminal.getOutcome()
            == EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_ABORTED
        && !request.getAbortRequestId().equals(terminal.getAbortRequestId()))
      throw EntityCharacterCreateBindings.conflict();
    // A winning COMMITTED result is returned unchanged: an abort never overwrites it.
    return terminal;
  }

  public EntityCharacterCreateTerminal read(ReadEntityCharacterCreateTerminalRequest request) {
    EntityCharacterCreatePeerInterceptor.requirePeer(namespace, false);
    EntityCharacterCreateRepository.outsideTransaction();
    EntityCharacterCreateBindings.exact(request);
    if (!request.hasOriginal()) throw EntityCharacterCreateBindings.invalid();
    EntityCharacterCreateBindings.requireOriginalRequest(request.getOriginal());
    if (!namespace.equals(request.getOriginal().getTargetNamespace()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
    return repository
        .read(request.getOriginal())
        .orElseThrow(
            () ->
                Status.NOT_FOUND
                    .withDescription("No definitive Entity creation terminal; absence is not abort")
                    .asRuntimeException());
  }

  private void requireMutation(CommitProtectedCharacterCreateRequest request) {
    EntityCharacterCreatePeerInterceptor.requirePeer(namespace, true);
    EntityCharacterCreateRepository.outsideTransaction();
    EntityCharacterCreateBindings.requireRequest(request);
    if (!namespace.equals(request.getOriginal().getTargetNamespace()))
      throw Status.PERMISSION_DENIED.asRuntimeException();
  }

  private EntityCharacterCreateProofs readAllOwners(CommitProtectedCharacterCreateRequest request) {
    var original = account.readOriginal(request.getOriginal());
    var action = EntityCharacterCreateBindings.requireOriginal(request.getOriginal(), original);
    var accountRequest =
        ReadOriginalCharacterCreateProtectionRequest.newBuilder()
            .setOriginal(request.getOriginal())
            .setAccountProtectionId(request.getAccountProtection().getProtectionId())
            .setAccountProtectionFence(request.getAccountProtection().getProtectionFence())
            .build();
    var accountProtection = account.readProtection(accountRequest);
    EntityCharacterCreateBindings.requireAccountProtection(request, original, accountProtection);
    var gameSessionProtection =
        gameSession.readOriginal(accountProtection, request.getGameSessionProtection());
    var worldProtection =
        world.readOriginal(
            ReadOriginalCharacterCreateLifecycleProtectionRequest.newBuilder()
                .setAccountProtection(accountRequest)
                .setWorldProtectionId(request.getWorldProtection().getProtectionId())
                .setWorldProtectionFence(request.getWorldProtection().getProtectionFence())
                .build());
    return new EntityCharacterCreateProofs(
        request,
        original,
        accountProtection,
        gameSessionProtection,
        worldProtection,
        source.read(action));
  }
}
