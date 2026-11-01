package net.firedevops.firemud.accountservice.service.session;

import java.util.Arrays;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * Frozen pre-V1017 TENANT persistence for explicitly selected historical migration fixtures.
 * Authentication, evidence capture, signing, registry, envelope and transaction owners are shared
 * with the current implementation. The extra in-memory scope field describes this old schema's
 * mandatory tenant contract; it is never written to historical storage or retained evidence.
 */
final class AccountHistoricalControlUiIssuanceRepository
    extends AccountControlUiIssuanceRepository {
  private final DSLContext historicalDsl;

  AccountHistoricalControlUiIssuanceRepository(DSLContext dsl) {
    super(dsl);
    historicalDsl = dsl;
  }

  @Override
  int insertPrepared(Object[] values) {
    if (values.length != 18 || !(values[4] instanceof UUID) || !"TENANT".equals(values[5])) {
      throw new IllegalStateException("Historical issuance requires the original TENANT layout");
    }
    Object[] historical = new Object[17];
    System.arraycopy(values, 0, historical, 0, 5);
    System.arraycopy(values, 6, historical, 5, 12);
    return historicalDsl.execute(
        "INSERT INTO account_control_ui_issuance_operations"
            + " (request_id, operation_id, token_jti, account_uuid, tenant_uuid, caller_workload,"
            + " caller_context_id, request_mac_key_id, request_digest, claims_payload, source_payload,"
            + " bundle_payload, signer_receipt, issued_at_epoch_second, expires_at_epoch_second,"
            + " registry_cleanup_deadline_epoch_millis, recovery_expires_at, status)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PREPARED')"
            + " ON CONFLICT (request_id) DO NOTHING",
        historical);
  }

  @Override
  Stored decodeStored(Record row) {
    if (row.get("tenant_uuid", UUID.class) == null) {
      throw new IllegalStateException("Historical issuance requires the original tenant");
    }
    // After upgrade, use the current strict decoder, including its actual stored scope.
    if (row.field("authorization_scope") != null) {
      return super.decodeStored(row);
    }
    Field<?>[] fields = Arrays.copyOf(row.fields(), row.size() + 1);
    fields[row.size()] = DSL.field("authorization_scope", String.class);
    Record historical = historicalDsl.newRecord(fields);
    historical.fromArray(copyWithScope(row));
    return new Stored(historical);
  }

  private static Object[] copyWithScope(Record row) {
    Object[] values = Arrays.copyOf(row.intoArray(), row.size() + 1);
    values[row.size()] = "TENANT";
    return values;
  }
}
