package net.firedevops.firemud.gamesession.service.directtext;

import com.google.protobuf.InvalidProtocolBufferException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipRequest;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;

/** Exact frozen request and optional historical positive reply; never a gameplay binding. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "Generated protobuf messages are immutable; no builders are retained or exposed")
public record DirectTextAuthenticatedLobbyJoin(
    DirectTextAuthenticatedLobbyScope scope,
    JoinPublicProductionMembershipRequest request,
    State state,
    Optional<JoinPublicProductionMembershipResponse> reply) {
  public static final int MAX_REQUEST_BYTES = 8192;
  public static final int MAX_REPLY_BYTES = 1024;

  public enum State {
    NEVER_DISPATCHED,
    DISPATCH_POSSIBLE,
    COMPLETED
  }

  public DirectTextAuthenticatedLobbyJoin {
    if (scope == null || request == null || state == null || reply == null) throw invalid();
    UUID id;
    try {
      id = UUID.fromString(request.getRequestId());
      DirectTextAuthenticatedLobbyScope.requireV4(id);
    } catch (RuntimeException malformed) {
      throw invalid();
    }
    if (!id.toString().equals(request.getRequestId())
        || id.equals(scope.original().requestId())
        || id.equals(scope.original().callerContextId())
        || id.equals(scope.recordId())
        || id.equals(scope.transport().proxyConnectionId())
        || id.equals(scope.evidence().tokenJti())
        || request.getSerializedSize() > MAX_REQUEST_BYTES
        || !request.equals(requestFor(scope, id))
        || (state == State.COMPLETED) != reply.isPresent()) throw invalid();
    reply.ifPresent(value -> requirePositiveReply(scope, value));
  }

  public UUID requestId() {
    return UUID.fromString(request.getRequestId());
  }

  public static PlayerExecutionContext contextFor(
      DirectTextAuthenticatedLobbyScope scope, UUID id) {
    return contextFor(
        scope.evidence().accountId().toString(), scope.transport(), scope.issued().target(), id);
  }

  public static PlayerExecutionContext contextFor(
      String accountId,
      net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport transport,
      net.firedevops.firemud.gamesession.client.DirectTextConnectScopeTarget target,
      UUID id) {
    DirectTextAuthenticatedLobbyScope.requireV4(id);
    return PlayerExecutionContext.newBuilder()
        .setAccountId(accountId)
        .setTenantId(target.tenantId())
        .setRealmId(target.realmId())
        .setPlayableStateNamespaceId(target.playableStateNamespaceId())
        .setPlayableStateScope(target.playableStateScope())
        .setGameInstanceId(target.gameInstanceId())
        .setSessionId(transport.transportSessionId())
        .setRequestId(id.toString())
        .build();
  }

  public static JoinPublicProductionMembershipRequest requestFor(
      DirectTextAuthenticatedLobbyScope scope, UUID id) {
    return JoinPublicProductionMembershipRequest.newBuilder()
        .setPlayerContext(contextFor(scope, id))
        .setConnectScopeId(scope.issued().connectScopeId())
        .setRequestId(id.toString())
        .build();
  }

  public static JoinPublicProductionMembershipRequest decodeRequest(byte[] bytes) {
    try {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES) throw invalid();
      var request = JoinPublicProductionMembershipRequest.parseFrom(bytes);
      if (!request.getUnknownFields().asMap().isEmpty()
          || !request.hasPlayerContext()
          || !request.getPlayerContext().getUnknownFields().asMap().isEmpty()
          || !Arrays.equals(bytes, request.toByteArray())) throw invalid();
      return request;
    } catch (InvalidProtocolBufferException | RuntimeException failure) {
      throw invalid();
    }
  }

  public static JoinPublicProductionMembershipResponse decodeReply(
      DirectTextAuthenticatedLobbyScope scope, byte[] bytes) {
    try {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_REPLY_BYTES) throw invalid();
      var reply = JoinPublicProductionMembershipResponse.parseFrom(bytes);
      requirePositiveReply(scope, reply);
      if (!Arrays.equals(bytes, reply.toByteArray())) throw invalid();
      return reply;
    } catch (InvalidProtocolBufferException | RuntimeException failure) {
      throw invalid();
    }
  }

  /** AccountClient's locally manufactured errors cannot prove a negative terminal operation. */
  public static void requirePositiveReply(
      DirectTextAuthenticatedLobbyScope scope, JoinPublicProductionMembershipResponse reply) {
    if (reply == null
        || !reply.getUnknownFields().asMap().isEmpty()
        || reply.hasError()
        || !reply.getSuccess()
        || !Set.of("JOINED", "ALREADY_ACTIVE").contains(reply.getOutcomeCode())
        || !scope.evidence().accountId().toString().equals(reply.getAccountId())
        || !scope.issued().target().tenantId().equals(reply.getTenantId())
        || !reply.getMembershipId().matches("[1-9][0-9]{0,18}")
        || reply.getMembershipVersion() <= 0
        || reply.getMembershipAuthorityGeneration() <= 0
        || reply.getSerializedSize() > MAX_REPLY_BYTES) throw invalid();
    RequestIdValidation.requirePositiveLong(reply.getMembershipId(), "membershipId");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Exact bounded lobby JOIN original required");
  }

  @Override
  public String toString() {
    return "DirectTextAuthenticatedLobbyJoin[nonsecret,non-admitting]";
  }
}
