package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Actual hosting envelope codec with controlled ledger projections; no paid SQL owner proof. */
class AccountHostingBillingStreamReadTest {
  private static final UUID TENANT = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID REQUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID EVENT = TenantAuthorityEventV1Codec.tenantBillingEventId(REQUEST);
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void actualHostingEncoderRoundTripsBothCutoffProfilesThroughSharedCheckpointWithoutDemoDecode() {
    for (boolean cutoff : new boolean[] {false, true}) {
      var event = hosting(cutoff);
      var decoded = AccountHostingSubscriptionLifecycleEvidence.verifyBilling(event.payload());
      assertThat(decoded.matches(event.payload(), event.digest())).isTrue();
      assertThat(decoded.requestDigest).isEqualTo(REQUEST_DIGEST);
      DSLContext dsl = mock(DSLContext.class);
      doReturn(stream(7L), row(event)).when(dsl).fetchOne(anyString(), any(Object[].class));
      TransactionSynchronizationManager.setActualTransactionActive(true);
      var repository = new AccountTenantEntitlementOutboxRepository(dsl);

      assertThat(repository.readCheckpoint(TENANT))
          .contains(
              new AccountTenantEntitlementOutboxRepository.Checkpoint(
                  AccountTenantEntitlementOutboxRepository.streamKey(TENANT),
                  7L,
                  EVENT,
                  event.digest()));

      // A hosting event remains incompatible with the deliberately DEMO-only Event API.
      doReturn(row(event)).when(dsl).fetchOne(anyString(), any(Object[].class));
      assertThatThrownBy(() -> repository.readEvent(TENANT, 7L))
          .isInstanceOf(IllegalArgumentException.class);
      verify(dsl, never()).execute(anyString(), any(Object[].class));
    }
  }

  @Test
  void sharedCheckpointRejectsChangedPersistedIdentitySequenceOrDigest() {
    for (String field :
        List.of(
            "tenant_uuid", "tenant_billing_sequence", "request_id", "event_id", "event_digest")) {
      var event = hosting(false);
      Record row = row(event);
      switch (field) {
        case "tenant_billing_sequence" -> row.set(DSL.field(field, Long.class), 8L);
        case "event_digest" -> row.set(DSL.field(field, String.class), "sha256:" + "b".repeat(64));
        default -> row.set(DSL.field(field, UUID.class), UUID.randomUUID());
      }
      DSLContext dsl = mock(DSLContext.class);
      doReturn(stream(7L), row).when(dsl).fetchOne(anyString(), any(Object[].class));
      TransactionSynchronizationManager.setActualTransactionActive(true);

      assertThatThrownBy(
              () -> new AccountTenantEntitlementOutboxRepository(dsl).readCheckpoint(TENANT))
          .as("changed %s", field)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void inverseRejectsUnknownNoncanonicalDuplicateOversizedAndContradictoryEnvelopes() {
    byte[] original = hosting(true).payload();
    for (Consumer<Map<String, Object>> mutation :
        List.<Consumer<Map<String, Object>>>of(
            fields -> fields.put("schemaVersion", "unknown/v1"),
            fields -> fields.put("unknown", true),
            fields -> fields.remove("requestDigest"),
            fields -> fields.put("tenantBillingSequence", "07"),
            fields -> fields.put("eventId", "55555555-5555-4555-8555-555555555555"),
            fields -> fields.put("tenantId", new UUID(0L, 0L).toString()),
            fields -> fields.put("tenantBillingStateChanged", false),
            fields -> fields.put("eventTypes", List.of("SubscriptionStatusChanged")),
            fields -> fields.put("receipt", Map.of("hardCutoff", false)))) {
      Map<String, Object> fields = JSON.readValue(original, new TypeReference<>() {});
      mutation.accept(fields);
      assertThatThrownBy(
              () ->
                  AccountHostingSubscriptionLifecycleEvidence.verifyBilling(
                      AccountHostingSubscriptionLifecycleEvidence.encode(fields)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    String text = new String(original, StandardCharsets.UTF_8);
    for (byte[] invalid :
        List.of(
            (" " + text).getBytes(StandardCharsets.UTF_8),
            (text + " {}").getBytes(StandardCharsets.UTF_8),
            text.replaceFirst("\\{", "{\"eventType\":\"SubscriptionStatusChanged\",")
                .getBytes(StandardCharsets.UTF_8),
            new byte[32769])) {
      assertThatThrownBy(() -> AccountHostingSubscriptionLifecycleEvidence.verifyBilling(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    // Unknown and tampered serialized envelopes are also refused at the shared reader boundary.
    for (byte[] invalid :
        List.of(
            text.replace("account-hosting-subscription-event/v1", "unknown/v1")
                .getBytes(StandardCharsets.UTF_8),
            text.replace("SubscriptionStatusChanged", "OtherEvent")
                .getBytes(StandardCharsets.UTF_8))) {
      Record row = row(hosting(true));
      row.set(DSL.field("payload", byte[].class), invalid);
      row.set(
          DSL.field("event_digest", String.class),
          AccountHostingSubscriptionLifecycleEvidence.digest(invalid));
      DSLContext dsl = mock(DSLContext.class);
      doReturn(stream(7L), row).when(dsl).fetchOne(anyString(), any(Object[].class));
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(
              () -> new AccountTenantEntitlementOutboxRepository(dsl).readCheckpoint(TENANT))
          .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
    }
  }

  private static AccountHostingSubscriptionLifecycleEvidence.BillingEvent hosting(boolean cutoff) {
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("schemaVersion", "account-hosting-current-receipt/v1");
    receipt.put("tenantId", TENANT.toString());
    receipt.put("requestId", REQUEST.toString());
    receipt.put("requestDigest", REQUEST_DIGEST);
    receipt.put("hardCutoff", cutoff);
    return AccountHostingSubscriptionLifecycleEvidence.billing(
        TENANT, REQUEST, REQUEST_DIGEST, 7L, EVENT, receipt);
  }

  private static Record stream(long value) {
    var sequence = DSL.field("last_sequence", Long.class);
    Record row = DSL.using(SQLDialect.POSTGRES).newRecord(sequence);
    row.set(sequence, value);
    return row;
  }

  private static Record row(AccountHostingSubscriptionLifecycleEvidence.BillingEvent event) {
    var tenant = DSL.field("tenant_uuid", UUID.class);
    var sequence = DSL.field("tenant_billing_sequence", Long.class);
    var request = DSL.field("request_id", UUID.class);
    var id = DSL.field("event_id", UUID.class);
    var digest = DSL.field("event_digest", String.class);
    var payload = DSL.field("payload", byte[].class);
    Record row =
        DSL.using(SQLDialect.POSTGRES).newRecord(tenant, sequence, request, id, digest, payload);
    row.set(tenant, event.tenantId);
    row.set(sequence, event.sequence);
    row.set(request, event.requestId);
    row.set(id, event.eventId);
    row.set(digest, event.digest());
    row.set(payload, event.payload());
    return row;
  }
}
