package net.firedevops.firemud.socialgroups.client;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.account.v1.AccountServiceGrpc;
import net.firedevops.firemud.account.v1.GetProfileRequest;
import net.firedevops.firemud.account.v1.GetProfileResponse;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesRequest;
import net.firedevops.firemud.account.v1.ListPresenceVisibilityPoliciesResponse;
import net.firedevops.firemud.account.v1.UpdateProfileRequest;
import net.firedevops.firemud.account.v1.UpdateProfileResponse;
import net.firedevops.firemud.common.account.AccountProfileJson;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public final class AccountClient
    extends AbstractBlockingGrpcClient<AccountServiceGrpc.AccountServiceBlockingStub> {
  private static final Logger logger = LoggerFactory.getLogger(AccountClient.class);
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final int PRESENCE_VISIBILITY_POLICY_BATCH_SIZE = 100;
  private static final String PRESENCE_VISIBILITY_POLICY_READ_OPERATION =
      "AccountService.listPresenceVisibilityPolicies";

  private final MeterRegistry meterRegistry;

  public AccountClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      MeterRegistry meterRegistry) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
    this.meterRegistry = meterRegistry;
  }

  @PostConstruct
  void init() throws SSLException {
    initClient();
  }

  public Optional<FriendPresenceVisibilityPolicyValue> getPresenceVisibilityPolicy(
      long tenantId, long accountId) {
    return getProfileSnapshot(tenantId, accountId)
        .map(AccountProfileSnapshot::presenceVisibilityPolicy);
  }

  /** Returns only current persisted policies; callers must fail closed for absent entries. */
  public Map<Long, FriendPresenceVisibilityPolicyValue> getPresenceVisibilityPolicies(
      long tenantId, Collection<Long> accountIds) {
    if (stub() == null || accountIds == null || accountIds.isEmpty()) {
      return Map.of();
    }
    List<Long> requestedAccountIds =
        accountIds.stream()
            .filter(accountId -> accountId != null && accountId > 0)
            .distinct()
            .toList();
    if (requestedAccountIds.isEmpty()) {
      return Map.of();
    }
    try {
      Map<Long, FriendPresenceVisibilityPolicyValue> policies = new LinkedHashMap<>();
      for (int offset = 0;
          offset < requestedAccountIds.size();
          offset += PRESENCE_VISIBILITY_POLICY_BATCH_SIZE) {
        List<Long> batch =
            requestedAccountIds.subList(
                offset,
                Math.min(
                    offset + PRESENCE_VISIBILITY_POLICY_BATCH_SIZE, requestedAccountIds.size()));
        ListPresenceVisibilityPoliciesRequest.Builder request =
            ListPresenceVisibilityPoliciesRequest.newBuilder().setTenantId(Long.toString(tenantId));
        batch.forEach(accountId -> request.addAccountIds(Long.toString(accountId)));

        ListPresenceVisibilityPoliciesResponse response =
            callStub().listPresenceVisibilityPolicies(request.build());
        if (response.hasError()) {
          GrpcAppErrors.countIfError(meterRegistry, response.getError());
          GrpcAppErrors.logIfError(
              logger, PRESENCE_VISIBILITY_POLICY_READ_OPERATION, response.getError());
          return Map.of();
        }
        response
            .getPoliciesList()
            .forEach(
                entry -> {
                  try {
                    long accountId = Long.parseLong(entry.getAccountId());
                    if (accountId > 0 && batch.contains(accountId)) {
                      policies.put(
                          accountId,
                          FriendPresenceVisibilityPolicyValue.valueOf(entry.getPolicy()));
                    }
                  } catch (IllegalArgumentException ignored) {
                    // Missing or malformed policy entries remain fail-closed at the caller.
                  }
                });
      }
      return Map.copyOf(policies);
    } catch (Exception ex) {
      logger.warn(
          "Failed to resolve account presence visibility policies tenantId={} accountCount={}",
          tenantId,
          requestedAccountIds.size(),
          ex);
      return Map.of();
    }
  }

  public boolean updatePresenceVisibilityPolicy(
      long tenantId, long accountId, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    if (stub() == null || visibilityPolicy == null) {
      return false;
    }
    try {
      AccountProfileSnapshot snapshot = getProfileSnapshot(tenantId, accountId).orElse(null);
      if (snapshot == null) {
        return false;
      }
      UpdateProfileResponse response =
          callStub()
              .updateProfile(
                  UpdateProfileRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setAccountId(Long.toString(accountId))
                      .setProfileJson(
                          new AccountProfileJson(
                                  snapshot.displayName(), snapshot.bio(), visibilityPolicy.name())
                              .toJson())
                      .build());
      return response.getSuccess() && !response.hasError();
    } catch (Exception ex) {
      logger.warn(
          "Failed to update account presence visibility policy tenantId={} accountId={} policy={}",
          tenantId,
          accountId,
          visibilityPolicy,
          ex);
      return false;
    }
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAccountService();
  }

  @Override
  protected String defaultTarget() {
    return "account-service:6565";
  }

  @Override
  protected AccountServiceGrpc.AccountServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(AccountServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  private Optional<AccountProfileSnapshot> getProfileSnapshot(long tenantId, long accountId) {
    if (stub() == null) {
      return Optional.empty();
    }
    GetProfileRequest request =
        GetProfileRequest.newBuilder()
            .setTenantId(Long.toString(tenantId))
            .setAccountId(Long.toString(accountId))
            .build();
    try {
      GetProfileResponse response = callStub().getProfile(request);
      if (response.hasError() || response.getProfileJson().isBlank()) {
        return Optional.empty();
      }
      AccountProfileJson profile =
          AccountProfileJson.parse(
              response.getProfileJson(), FriendPresenceVisibilityPolicyValue.FRIENDS_ONLY.name());
      FriendPresenceVisibilityPolicyValue resolvedPolicy =
          FriendPresenceVisibilityPolicyValue.valueOf(profile.presenceVisibilityPolicy());
      return Optional.of(
          new AccountProfileSnapshot(profile.displayName(), profile.bio(), resolvedPolicy));
    } catch (Exception ex) {
      logger.warn(
          "Failed to resolve account presence visibility policy tenantId={} accountId={}",
          tenantId,
          accountId,
          ex);
      return Optional.empty();
    }
  }

  private AccountServiceGrpc.AccountServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }

  private record AccountProfileSnapshot(
      String displayName,
      String bio,
      FriendPresenceVisibilityPolicyValue presenceVisibilityPolicy) {}
}
