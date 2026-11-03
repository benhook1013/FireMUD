package net.firedevops.firemud.entitymanagement.bootstrap;

import java.util.HashSet;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterActor;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadRequest;
import net.firedevops.firemud.entitymanagement.v1.AccountBootstrapRosterReadResponse;
import net.firedevops.firemud.entitymanagement.validation.CanonicalEntitySourceValues;

/** Closed, bounded transient discovery contract. No original-selection or retention identities. */
public final class AccountBootstrapRosterReadContract {
  public static final int MAX_REQUEST_BYTES = 4096;
  public static final int MAX_RESPONSE_BYTES = 64 * 1024;
  public static final int MAX_ACTORS = 100;

  private AccountBootstrapRosterReadContract() {}

  public static CanonicalGameplayRosterTarget requireRequest(
      AccountBootstrapRosterReadRequest wire) {
    if (wire == null
        || wire.getSerializedSize() > MAX_REQUEST_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()
        || !wire.hasExpectedTarget()
        || !wire.getExpectedTarget().getUnknownFields().asMap().isEmpty()
        || !GrpcPeerIdentity.isValidNamespace(wire.getTargetNamespace())) {
      throw new IllegalArgumentException("Complete closed bootstrap roster request required");
    }
    uuid(wire.getRequestUuid());
    uuid(wire.getCanonicalAccountUuid());
    var target = wire.getExpectedTarget();
    uuid(target.getTenantUuid());
    uuid(target.getRealmUuid());
    uuid(target.getGameInstanceUuid());
    uuid(target.getCanonicalVersionUuid());
    uuid(target.getPlayableStateNamespaceUuid());
    var parsed = CanonicalGameplayRosterTarget.fromWire(target);
    if (!parsed.toWire().equals(target)) {
      throw new IllegalArgumentException("Canonical exact target required");
    }
    return parsed;
  }

  public static AccountBootstrapRosterReadResponse requireResponse(
      AccountBootstrapRosterReadRequest request, AccountBootstrapRosterReadResponse response) {
    requireRequest(request);
    if (response == null
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.hasRequest()
        || !request.equals(response.getRequest())
        || response.getActorsCount() > MAX_ACTORS) {
      throw new IllegalArgumentException("Bounded exact bootstrap roster response required");
    }
    String previous = null;
    var seen = new HashSet<UUID>();
    for (var actor : response.getActorsList()) {
      if (!actor.getUnknownFields().asMap().isEmpty()) {
        throw new IllegalArgumentException("Closed actor projection required");
      }
      UUID actorUuid = uuid(actor.getCharacterUuid());
      new CanonicalGameplayRosterActor(actorUuid, actor.getDisplayName());
      if (!actor.hasLevel() || actor.getLevel() < 0) {
        throw new IllegalArgumentException("Actual persisted actor level required");
      }
      if (!seen.add(actorUuid)
          || (previous != null && previous.compareTo(actor.getCharacterUuid()) >= 0)) {
        throw new IllegalArgumentException("Unique UUID-ordered actor projection required");
      }
      previous = actor.getCharacterUuid();
    }
    return response;
  }

  public static UUID uuid(String value) {
    return CanonicalEntitySourceValues.parseCanonicalNonNilUuid(value);
  }
}
