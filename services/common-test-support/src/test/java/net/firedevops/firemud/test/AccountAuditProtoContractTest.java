package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.v1.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.v1.CreateLogEventResponse;
import net.firedevops.firemud.loggingadmin.v1.ReadLogEventReceiptResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;

class AccountAuditProtoContractTest {
  @Test
  void createRequestKeepsTenantIdentityAndReservesRemovedLegacyFields() {
    Descriptor descriptor = CreateLogEventRequest.getDescriptor();

    assertField(descriptor, "tenant_id", 1, FieldDescriptor.Type.STRING);
    assertField(descriptor, "scope", 5, FieldDescriptor.Type.ENUM);
    assertField(descriptor, "audit_event_id", 6, FieldDescriptor.Type.STRING);
    assertField(descriptor, "producer_service", 7, FieldDescriptor.Type.STRING);
    assertField(descriptor, "event_type", 8, FieldDescriptor.Type.STRING);
    assertField(descriptor, "occurred_at", 9, FieldDescriptor.Type.MESSAGE);
    assertField(descriptor, "schema_version", 10, FieldDescriptor.Type.INT32);
    assertField(descriptor, "payload", 11, FieldDescriptor.Type.BYTES);
    assertField(descriptor, "payload_digest_version", 12, FieldDescriptor.Type.INT32);
    assertField(descriptor, "payload_digest", 13, FieldDescriptor.Type.STRING);
    assertThat(descriptor.findFieldByNumber(2)).isNull();
    assertThat(descriptor.findFieldByNumber(3)).isNull();
    assertThat(descriptor.findFieldByNumber(4)).isNull();
    assertThat(descriptor.toProto().getReservedRangeCount()).isEqualTo(1);
    assertThat(descriptor.toProto().getReservedRange(0).getStart()).isEqualTo(2);
    assertThat(descriptor.toProto().getReservedRange(0).getEnd()).isEqualTo(5);
    assertThat(descriptor.toProto().getReservedNameList())
        .containsExactlyInAnyOrder("account_id", "type", "message");
  }

  @Test
  void createResponseKeepsLegacyIdentityAndErrorNumbers() {
    Descriptor descriptor = CreateLogEventResponse.getDescriptor();

    assertField(descriptor, "log_event_id", 1, FieldDescriptor.Type.STRING);
    assertErrorField(descriptor, "error", 2);
    assertField(descriptor, "scope", 3, FieldDescriptor.Type.ENUM);
    assertField(descriptor, "tenant_id", 4, FieldDescriptor.Type.STRING);
    assertField(descriptor, "audit_event_id", 5, FieldDescriptor.Type.STRING);
    assertField(descriptor, "receipt_id", 6, FieldDescriptor.Type.STRING);
    assertField(descriptor, "schema_version", 7, FieldDescriptor.Type.INT32);
    assertField(descriptor, "payload_digest_version", 8, FieldDescriptor.Type.INT32);
    assertField(descriptor, "payload_digest", 9, FieldDescriptor.Type.STRING);
    assertField(descriptor, "status", 10, FieldDescriptor.Type.ENUM);
    assertField(descriptor, "outcome", 11, FieldDescriptor.Type.ENUM);
    assertField(descriptor, "audit_projection_version", 12, FieldDescriptor.Type.INT32);
    assertThat(descriptor.findFieldByName("scope").getEnumType())
        .isEqualTo(AccountAuditScope.getDescriptor());
    assertThat(descriptor.findFieldByName("status").getEnumType())
        .isEqualTo(AccountAuditReceiptStatus.getDescriptor());
    assertThat(descriptor.findFieldByName("outcome").getEnumType())
        .isEqualTo(AccountAuditReceiptOutcome.getDescriptor());
  }

  @Test
  void receiptReadResponseAddsErrorDetailAtTheNextFreeNumber() {
    Descriptor descriptor = ReadLogEventReceiptResponse.getDescriptor();

    assertField(descriptor, "scope", 1, FieldDescriptor.Type.ENUM);
    assertField(descriptor, "tenant_id", 2, FieldDescriptor.Type.STRING);
    assertField(descriptor, "audit_event_id", 3, FieldDescriptor.Type.STRING);
    assertField(descriptor, "receipt_id", 4, FieldDescriptor.Type.STRING);
    assertField(descriptor, "log_event_id", 5, FieldDescriptor.Type.STRING);
    assertField(descriptor, "schema_version", 6, FieldDescriptor.Type.INT32);
    assertField(descriptor, "payload_digest_version", 7, FieldDescriptor.Type.INT32);
    assertField(descriptor, "payload_digest", 8, FieldDescriptor.Type.STRING);
    assertField(descriptor, "status", 9, FieldDescriptor.Type.ENUM);
    assertField(descriptor, "outcome", 10, FieldDescriptor.Type.ENUM);
    assertErrorField(descriptor, "error", 11);
    assertField(descriptor, "audit_projection_version", 12, FieldDescriptor.Type.INT32);
  }

  private static void assertField(
      Descriptor descriptor, String name, int number, FieldDescriptor.Type type) {
    FieldDescriptor field = descriptor.findFieldByName(name);
    assertThat(field).isNotNull();
    assertThat(field.getNumber()).isEqualTo(number);
    assertThat(field.getType()).isEqualTo(type);
  }

  private static void assertErrorField(Descriptor descriptor, String name, int number) {
    assertField(descriptor, name, number, FieldDescriptor.Type.MESSAGE);
    assertThat(descriptor.findFieldByName(name).getMessageType())
        .isEqualTo(ErrorDetail.getDescriptor());
  }
}
