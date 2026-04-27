package net.firedevops.firemud.gamedesign.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.firedevops.firemud.gamedesign.config.PluginSignerProperties;
import net.firedevops.firemud.gamedesign.service.ParsedPluginBundle;
import net.firedevops.firemud.gamedesign.service.PluginAssetRef;
import net.firedevops.firemud.gamedesign.service.PluginBundleIntakeService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class PluginBundleIntakeServiceImpl implements PluginBundleIntakeService {
  private static final long MAX_BUNDLE_BYTES = 10L * 1024L * 1024L;
  private static final long MAX_EXPANDED_BYTES = 20L * 1024L * 1024L;
  private static final int MAX_FILE_COUNT = 256;
  private static final int MAX_COMPRESSION_RATIO = 50;

  private final ObjectMapper objectMapper;
  private final PluginSignerProperties signerProperties;

  public PluginBundleIntakeServiceImpl(
      ObjectMapper objectMapper, PluginSignerProperties signerProperties) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.signerProperties =
        Objects.requireNonNull(signerProperties, "signerProperties must not be null");
  }

  @Override
  public ParsedPluginBundle parseAndVerify(byte[] bundleBytes) {
    if (bundleBytes == null || bundleBytes.length == 0) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: bundleBytes is required");
    }
    if (bundleBytes.length > MAX_BUNDLE_BYTES) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: bundle_too_large");
    }

    Map<String, byte[]> files = extractFiles(bundleBytes);
    PluginManifest manifest = parseManifest(files);
    SignatureEnvelope signatureEnvelope = parseSignatures(files);
    requireReferencedFiles(manifest, files);
    String bundleDigest = canonicalBundleDigest(files);
    VerifiedSignature verifiedSignature = verifySignature(signatureEnvelope, bundleDigest);
    requireAssetIntegrity(manifest.assetRefs(), files);
    return new ParsedPluginBundle(
        manifest.pluginId(),
        manifest.pluginVersionId(),
        manifest.baseVersionId(),
        manifest.abilitySchemaDigest(),
        bundleDigest,
        manifest.schemaVersion(),
        verifiedSignature.signerKeyId(),
        manifest.assetRefs(),
        files);
  }

  private Map<String, byte[]> extractFiles(byte[] bundleBytes) {
    Map<String, byte[]> files = new LinkedHashMap<>();
    long expandedBytes = 0L;
    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bundleBytes))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
          throw new IllegalArgumentException("UPLOAD_REJECTED: bundle_path_not_allowed");
        }
        if (files.containsKey(name)) {
          throw new IllegalArgumentException("UPLOAD_REJECTED: bundle_duplicate_path");
        }
        if (files.size() >= MAX_FILE_COUNT) {
          throw new IllegalArgumentException("UPLOAD_REJECTED: bundle_file_count_exceeded");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        input.transferTo(output);
        byte[] bytes = output.toByteArray();
        expandedBytes += bytes.length;
        if (expandedBytes > MAX_EXPANDED_BYTES) {
          throw new IllegalArgumentException("UPLOAD_REJECTED: bundle_expanded_bytes_exceeded");
        }
        files.put(name, bytes);
      }
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: bundle_parse_failed");
    }
    if (bundleBytes.length > 0 && expandedBytes / bundleBytes.length > MAX_COMPRESSION_RATIO) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: bundle_compression_ratio_exceeded");
    }
    return files;
  }

  private PluginManifest parseManifest(Map<String, byte[]> files) {
    byte[] manifestBytes = requireFile(files, "plugin-manifest.json", "manifest_missing");
    try {
      PluginManifest manifest = objectMapper.readValue(manifestBytes, PluginManifest.class);
      requireText(manifest.pluginId(), "manifest_plugin_id_missing");
      requireText(manifest.pluginVersionId(), "manifest_plugin_version_missing");
      requireText(manifest.abilitySchemaDigest(), "manifest_ability_schema_digest_missing");
      if (manifest.baseVersionId() <= 0L) {
        throw new IllegalArgumentException("UPLOAD_REJECTED: manifest_base_version_invalid");
      }
      if (manifest.schemaVersion() <= 0) {
        throw new IllegalArgumentException("UPLOAD_REJECTED: manifest_schema_version_invalid");
      }
      return manifest;
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: manifest_parse_failed");
    }
  }

  private SignatureEnvelope parseSignatures(Map<String, byte[]> files) {
    byte[] signatureBytes = requireFile(files, "signatures.json", "signatures_missing");
    try {
      SignatureEnvelope envelope = objectMapper.readValue(signatureBytes, SignatureEnvelope.class);
      if (envelope.signatures() == null || envelope.signatures().isEmpty()) {
        throw new IllegalArgumentException("UPLOAD_REJECTED: signatures_missing");
      }
      return envelope;
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: signatures_parse_failed");
    }
  }

  private void requireReferencedFiles(PluginManifest manifest, Map<String, byte[]> files) {
    for (PluginEntrypoint entrypoint : manifest.entrypoints()) {
      requireText(entrypoint.path(), "entrypoint_path_missing");
      requireFile(files, entrypoint.path(), "entrypoint_missing");
    }
    for (PluginAssetRef assetRef : manifest.assetRefs()) {
      requireText(assetRef.assetId(), "asset_id_missing");
      requireText(assetRef.path(), "asset_path_missing");
      requireText(assetRef.contentHash(), "asset_hash_missing");
      requireText(assetRef.contentType(), "asset_content_type_missing");
      requireFile(files, assetRef.path(), "asset_missing");
    }
  }

  private void requireAssetIntegrity(List<PluginAssetRef> assetRefs, Map<String, byte[]> files) {
    for (PluginAssetRef assetRef : assetRefs) {
      byte[] bytes = requireFile(files, assetRef.path(), "asset_missing");
      String actualDigest = sha256(bytes);
      if (!actualDigest.equals(assetRef.contentHash())) {
        throw new IllegalArgumentException("UPLOAD_REJECTED: asset_digest_mismatch");
      }
    }
  }

  private VerifiedSignature verifySignature(SignatureEnvelope envelope, String bundleDigest) {
    List<String> allowlistedKeys = new ArrayList<>(signerProperties.getPublicKeys().keySet());
    if (allowlistedKeys.isEmpty()) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: signer_not_allowed");
    }
    for (SignatureEntry entry : envelope.signatures()) {
      if (!bundleDigest.equals(entry.bundleDigest())) {
        continue;
      }
      String publicKeyPem = signerProperties.getPublicKeys().get(entry.signerKeyId());
      if (publicKeyPem == null || publicKeyPem.isBlank()) {
        continue;
      }
      if (verifyEd25519(publicKeyPem, bundleDigest, entry.ed25519Signature())) {
        return new VerifiedSignature(entry.signerKeyId());
      }
    }
    if (envelope.signatures().stream()
        .noneMatch(entry -> signerProperties.getPublicKeys().containsKey(entry.signerKeyId()))) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: signer_not_allowed");
    }
    throw new IllegalArgumentException("UPLOAD_REJECTED: signature_verification_failed");
  }

  private boolean verifyEd25519(String encodedKey, String bundleDigest, String encodedSignature) {
    try {
      byte[] keyBytes = Base64.getDecoder().decode(encodedKey);
      PublicKey publicKey =
          KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
      Signature signature = Signature.getInstance("Ed25519");
      signature.initVerify(publicKey);
      signature.update(bundleDigest.getBytes(StandardCharsets.UTF_8));
      return signature.verify(Base64.getDecoder().decode(encodedSignature));
    } catch (Exception ex) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: signature_verification_failed");
    }
  }

  private String canonicalBundleDigest(Map<String, byte[]> files) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      files.entrySet().stream()
          .filter(entry -> !"signatures.json".equals(entry.getKey()))
          .sorted(Comparator.comparing(Map.Entry::getKey))
          .forEach(
              entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue());
                digest.update((byte) 0);
              });
      byte[] hash = digest.digest();
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte current : hash) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte current : hash) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private byte[] requireFile(Map<String, byte[]> files, String path, String reasonCode) {
    byte[] bytes = files.get(path);
    if (bytes == null) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: " + reasonCode);
    }
    return bytes;
  }

  private void requireText(String value, String reasonCode) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("UPLOAD_REJECTED: " + reasonCode);
    }
  }

  private record VerifiedSignature(String signerKeyId) {}

  private record PluginManifest(
      int schemaVersion,
      String pluginId,
      String pluginVersionId,
      long baseVersionId,
      String abilitySchemaDigest,
      List<PluginEntrypoint> entrypoints,
      List<PluginAssetRef> assetRefs) {
    PluginManifest {
      entrypoints = entrypoints == null ? List.of() : List.copyOf(entrypoints);
      assetRefs = assetRefs == null ? List.of() : List.copyOf(assetRefs);
    }
  }

  private record PluginEntrypoint(String graphId, String path) {}

  private record SignatureEnvelope(List<SignatureEntry> signatures) {
    SignatureEnvelope {
      signatures = signatures == null ? List.of() : List.copyOf(signatures);
    }
  }

  private record SignatureEntry(String bundleDigest, String signerKeyId, String ed25519Signature) {}
}
