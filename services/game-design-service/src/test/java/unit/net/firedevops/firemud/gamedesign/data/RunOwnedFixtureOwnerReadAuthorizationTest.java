package net.firedevops.firemud.gamedesign.data;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.GetAuthoredWorldVersionStateRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveAuthoredWorldSourceRequest;
import org.junit.jupiter.api.Test;

/** Controlled configuration/peer doubles; committed producer/storage proof is native. */
class RunOwnedFixtureOwnerReadAuthorizationTest {
  @Test
  void missingFixedConfigurationFailsClosedWithoutOwnerAccess() {
    var readback = mock(RunOwnedFixtureLaunchReadback.class);
    var policy = mock(RunOwnedFixturePolicyProducer.class);
    assertThatThrownBy(
            () ->
                new RunOwnedFixtureOwnerReadAuthorization(
                    readback, policy, "dev", "", "", "", "", "", "", ""))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(readback, policy);
  }

  @Test
  void callerMissingPeerWrongNamespaceServiceAndLeafDenyBeforeMalformedSelector() throws Exception {
    var readback = mock(RunOwnedFixtureLaunchReadback.class);
    var policy = mock(RunOwnedFixturePolicyProducer.class);
    var trusted = mock(TestDataSeeder.FixtureCapability.class);
    when(trusted.worldManagementLeafSha256()).thenReturn("a".repeat(64));
    try (var configuration = mockStatic(TestDataSeeder.class)) {
      configuration
          .when(
              () ->
                  TestDataSeeder.verifyFixtureConfiguration(
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString()))
          .thenReturn(trusted);
      var authorization =
          new RunOwnedFixtureOwnerReadAuthorization(
              readback,
              policy,
              "dev",
              "controlled-run",
              "controlled-project",
              "/controlled-capability",
              "/controlled-receipt",
              "/controlled-gs",
              "/controlled-world",
              "/controlled-ca");
      assertBothDeny(authorization);
      SessionContext.setContext("controlled-account", List.of("player"), Map.of());
      try {
        assertBothDeny(authorization);
      } finally {
        SessionContext.clear();
      }
      X509Certificate controlledLeaf = mock(X509Certificate.class);
      when(controlledLeaf.getEncoded()).thenReturn(new byte[] {1, 2, 3});
      var certificate = GrpcPeerCertificateEvidence.fromCertificate(controlledLeaf).orElseThrow();
      for (String uri :
          List.of(
              "spiffe://firemud/ns/dev/sa/game-session-service",
              "spiffe://firemud/ns/other/sa/world-management-service",
              "spiffe://firemud/ns/dev/sa/world-management-service")) {
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow())
            .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate)
            .run(() -> assertBothDeny(authorization));
      }
      verifyNoInteractions(readback, policy);
    }
  }

  @Test
  void rawReceiptAndQualifiedLaunchStillRequirePreexistingPolicyWithoutSealing() throws Exception {
    var readback = mock(RunOwnedFixtureLaunchReadback.class);
    var policy = mock(RunOwnedFixturePolicyProducer.class);
    var trusted = mock(TestDataSeeder.FixtureCapability.class);
    var capability =
        mock(
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec.Capability
                .class);
    var receipt =
        mock(
            net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                .SourceReceipt.class);
    var source = mock(net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence.class);
    var operation = java.util.UUID.randomUUID();
    var canonical = java.util.UUID.randomUUID();
    when(source.targetNamespace()).thenReturn("dev");
    when(source.operationId()).thenReturn(operation);
    when(source.canonicalTenantId()).thenReturn(canonical);
    when(source.worldSlug()).thenReturn("fixture-world");
    when(receipt.sourceEvidence()).thenReturn(source);
    var creation = mock(net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence.class);
    when(receipt.creationEvidence()).thenReturn(creation);
    when(trusted.wire()).thenReturn(capability);
    var committed =
        new RunOwnedFixtureLaunchReadback.CommittedReceipt(
            mock(RunOwnedFixtureLaunchReadback.Snapshot.class),
            mock(net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto.class));
    when(readback.readCommittedReceipt(receipt)).thenReturn(committed);
    when(policy.readExisting(trusted, creation, committed.snapshot(), committed.descriptor()))
        .thenThrow(new IllegalStateException("Preexisting sealed fixture policy unavailable"));
    X509Certificate controlledLeaf = mock(X509Certificate.class);
    when(controlledLeaf.getEncoded()).thenReturn(new byte[] {4, 5, 6});
    var certificate = GrpcPeerCertificateEvidence.fromCertificate(controlledLeaf).orElseThrow();
    when(trusted.worldManagementLeafSha256()).thenReturn(certificate.leafSha256());
    try (var configuration = mockStatic(TestDataSeeder.class);
        var files =
            mockStatic(
                net.firedevops.firemud.common.smoke.RunOwnedFixtureImmutableFileReader.class);
        var wire =
            mockStatic(
                net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                    .class)) {
      configuration
          .when(
              () ->
                  TestDataSeeder.verifyFixtureConfiguration(
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString(),
                      anyString()))
          .thenReturn(trusted);
      byte[] controlledReceipt = new byte[] {7};
      files
          .when(
              () ->
                  net.firedevops.firemud.common.smoke.RunOwnedFixtureImmutableFileReader
                      .readBoundedImmutableFile(any(java.nio.file.Path.class), anyInt(), any()))
          .thenReturn(controlledReceipt);
      wire.when(
              () ->
                  net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec
                      .parseSourceReceipt(controlledReceipt, capability))
          .thenReturn(receipt);
      var authorization =
          new RunOwnedFixtureOwnerReadAuthorization(
              readback,
              policy,
              "dev",
              "controlled-run",
              "controlled-project",
              "/controlled-capability",
              "/controlled-receipt",
              "/controlled-gs",
              "/controlled-world",
              "/controlled-ca");
      var request =
          net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.toReadRequest(
              new net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.ReadRequest(
                  "dev", java.util.UUID.randomUUID(), operation, canonical, "fixture-world"));
      Context.current()
          .withValue(
              GrpcPeerIdentity.CONTEXT_KEY,
              GrpcPeerIdentity.parseUri("spiffe://firemud/ns/dev/sa/world-management-service")
                  .orElseThrow())
          .withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate)
          .run(
              () ->
                  assertThatThrownBy(() -> authorization.authorizeSource(request))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining("Preexisting"));
      verify(readback).readCommittedReceipt(receipt);
      verify(policy).readExisting(trusted, creation, committed.snapshot(), committed.descriptor());
      verify(policy, never()).sealAndRead(any(), any(), any(), any());
    }
  }

  private static void assertBothDeny(RunOwnedFixtureOwnerReadAuthorization authorization) {
    assertThatThrownBy(
            () ->
                authorization.authorizeSource(
                    ResolveAuthoredWorldSourceRequest.getDefaultInstance()))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                authorization.authorizeVersion(
                    GetAuthoredWorldVersionStateRequest.getDefaultInstance()))
        .isInstanceOf(SecurityException.class);
  }
}
