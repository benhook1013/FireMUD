package net.firedevops.firemud.gamedesign.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class AssetStoreConfig {

  @Bean
  public S3Client s3Client(AssetStoreProperties properties) {
    AwsBasicCredentials creds =
        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey());
    return S3Client.builder()
        .endpointOverride(URI.create(properties.getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(creds))
        .region(Region.of(properties.getRegion()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
