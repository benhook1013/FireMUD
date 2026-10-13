package net.firedevops.firemud.accountservice.hostedterms;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Closed canonical encodings for trusted environment-owner binding publications and receipts. */
public final class HostedTermsEnvironmentBindingEncoding {
  private HostedTermsEnvironmentBindingEncoding() {}

  public static byte[] publication(
      UUID requestId, HostedTermsEnvironmentBinding.PublicationEvidence evidence) {
    HostedTermsEncoding.requireUuid(requestId, "environment binding publication request");
    Objects.requireNonNull(evidence);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-hosted-terms-environment-binding-publication/v1");
    fields.put("requestId", requestId.toString());
    fields.put("environmentBoundary", evidence.environmentBoundary());
    fields.put("hostedScopeId", evidence.hostedScopeId().toString());
    fields.put("operatorLegalIdentity", evidence.operatorLegalIdentity());
    fields.put("operatorIdentityVersion", Long.toString(evidence.operatorIdentityVersion()));
    fields.put("catalogVersionId", evidence.catalogVersionId().toString());
    fields.put("catalogSourceVersion", Long.toString(evidence.catalogSourceVersion()));
    fields.put("authenticatedPublisherIdentity", evidence.authenticatedPublisherIdentity());
    fields.put("publicationEventIdentity", evidence.publicationEventIdentity());
    fields.put(
        "predecessorBindingId",
        evidence.predecessorBindingId() == null
            ? null
            : evidence.predecessorBindingId().toString());
    fields.put(
        "predecessorSourceVersion",
        evidence.predecessorSourceVersion() == null
            ? null
            : Long.toString(evidence.predecessorSourceVersion()));
    return canonical(fields);
  }

  public static byte[] receipt(HostedTermsEnvironmentBinding binding) {
    Objects.requireNonNull(binding);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", "account-hosted-terms-environment-binding-receipt/v1");
    fields.put("bindingId", binding.bindingId().toString());
    fields.put("publicationRequestId", binding.publicationRequestId().toString());
    fields.put("environmentBoundary", binding.environmentBoundary());
    fields.put("hostedScopeId", binding.hostedScopeId().toString());
    fields.put("operatorLegalIdentity", binding.operatorLegalIdentity());
    fields.put("operatorIdentityVersion", Long.toString(binding.operatorIdentityVersion()));
    fields.put("catalogVersionId", binding.catalogVersionId().toString());
    fields.put("catalogSourceVersion", Long.toString(binding.catalogSourceVersion()));
    fields.put("authenticatedPublisherIdentity", binding.authenticatedPublisherIdentity());
    fields.put("publicationEventIdentity", binding.publicationEventIdentity());
    fields.put("publicationEvidenceDigest", binding.publicationEvidenceDigest());
    fields.put(
        "predecessorBindingId",
        binding.predecessorBindingId() == null ? null : binding.predecessorBindingId().toString());
    fields.put(
        "predecessorSourceVersion",
        binding.predecessorSourceVersion() == null
            ? null
            : Long.toString(binding.predecessorSourceVersion()));
    fields.put("sourceVersion", Long.toString(binding.sourceVersion()));
    return canonical(fields);
  }

  public static String publicationDigest(
      UUID requestId, HostedTermsEnvironmentBinding.PublicationEvidence evidence) {
    return HostedTermsEncoding.digest(publication(requestId, evidence));
  }

  public static String receiptDigest(HostedTermsEnvironmentBinding binding) {
    return HostedTermsEncoding.digest(receipt(binding));
  }

  private static byte[] canonical(Map<String, Object> fields) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(
          JsonMapper.builder().build().writeValueAsString(fields));
    } catch (IOException failure) {
      throw new IllegalArgumentException("Environment-binding evidence cannot be encoded", failure);
    }
  }
}
