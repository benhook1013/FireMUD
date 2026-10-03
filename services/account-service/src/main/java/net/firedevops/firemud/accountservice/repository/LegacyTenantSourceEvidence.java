package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Exact V26 retained Account source-row evidence for an operator-approved tenant association. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring transaction collaborator.")
public class LegacyTenantSourceEvidence {
  private static final String DOMAIN = "firemud/account-legacy-tenant-source/v1";

  private final DSLContext dsl;

  public LegacyTenantSourceEvidence(DSLContext dsl) {
    this.dsl = dsl;
  }

  public String digest(long legacyTenantId) {
    if (legacyTenantId <= 0) {
      throw new IllegalArgumentException("positive retained Account tenant key is required");
    }
    List<Record> accounts =
        dsl.fetch(
            "SELECT account_id, legacy_tenant_id, matching_membership_id, "
                + "matching_membership_admission_allowed, profile_tenant_count, "
                + "matching_profile_count, disposition, captured_at "
                + "FROM account_legacy_tenant_sources WHERE legacy_tenant_id = ? "
                + "ORDER BY account_id",
            legacyTenantId);
    if (accounts.isEmpty()) {
      throw new IllegalStateException("retained Account numeric tenant source is absent");
    }
    List<Record> memberships =
        dsl.fetch(
            "SELECT membership_id, account_id, tenant_id, "
                + "original_gameplay_admission_allowed, matches_account_legacy_tenant, "
                + "disposition, captured_at FROM account_legacy_membership_sources "
                + "WHERE tenant_id = ? ORDER BY membership_id",
            legacyTenantId);
    List<Record> profiles =
        dsl.fetch(
            "SELECT profile_id, account_id, tenant_id, matches_account_legacy_tenant, "
                + "disposition, captured_at FROM account_legacy_profile_sources "
                + "WHERE tenant_id = ? ORDER BY profile_id",
            legacyTenantId);

    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      try (DataOutputStream out =
          new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), hash))) {
        write(out, DOMAIN);
        out.writeLong(legacyTenantId);
        out.writeInt(accounts.size());
        for (Record row : accounts) {
          if (!"UNVERIFIED".equals(row.get(6, String.class))) {
            throw new IllegalStateException("retained Account tenant source is conflicting");
          }
          out.writeLong(row.get(0, Long.class));
          out.writeLong(row.get(1, Long.class));
          writeOptionalLong(out, row.get(2, Long.class));
          writeOptionalBoolean(out, row.get(3, Boolean.class));
          out.writeLong(row.get(4, Long.class));
          out.writeLong(row.get(5, Long.class));
          write(out, row.get(6, String.class));
          write(out, row.get(7, LocalDateTime.class).toString());
        }
        out.writeInt(memberships.size());
        for (Record row : memberships) {
          if (!Boolean.TRUE.equals(row.get(4, Boolean.class))
              || !"UNVERIFIED".equals(row.get(5, String.class))) {
            throw new IllegalStateException("retained Account membership source is conflicting");
          }
          out.writeLong(row.get(0, Long.class));
          out.writeLong(row.get(1, Long.class));
          out.writeLong(row.get(2, Long.class));
          out.writeBoolean(row.get(3, Boolean.class));
          out.writeBoolean(row.get(4, Boolean.class));
          write(out, row.get(5, String.class));
          write(out, row.get(6, LocalDateTime.class).toString());
        }
        out.writeInt(profiles.size());
        for (Record row : profiles) {
          if (!Boolean.TRUE.equals(row.get(3, Boolean.class))
              || !"UNVERIFIED".equals(row.get(4, String.class))) {
            throw new IllegalStateException("retained Account profile source is conflicting");
          }
          out.writeLong(row.get(0, Long.class));
          out.writeLong(row.get(1, Long.class));
          out.writeLong(row.get(2, Long.class));
          out.writeBoolean(row.get(3, Boolean.class));
          write(out, row.get(4, String.class));
          write(out, row.get(5, LocalDateTime.class).toString());
        }
      }
      return "sha256:" + HexFormat.of().formatHex(hash.digest());
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("retained Account source evidence cannot be digested", ex);
    }
  }

  private static void write(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static void writeOptionalLong(DataOutputStream out, Long value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) {
      out.writeLong(value);
    }
  }

  private static void writeOptionalBoolean(DataOutputStream out, Boolean value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) {
      out.writeBoolean(value);
    }
  }
}
