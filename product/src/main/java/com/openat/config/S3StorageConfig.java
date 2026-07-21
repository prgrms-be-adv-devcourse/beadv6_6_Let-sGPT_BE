package com.openat.config;

import java.net.URI;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {

  private static final Region LOCAL_REGION = Region.US_EAST_1;
  private static final S3Configuration LOCAL_SERVICE_CONFIGURATION =
      S3Configuration.builder().pathStyleAccessEnabled(true).build();

  @Bean
  public S3Client s3Client(S3StorageProperties properties) {
    S3ClientBuilder builder = S3Client.builder();
    localSettings(properties, properties.endpointOverride())
        .ifPresent(
            settings ->
                builder
                    .endpointOverride(settings.endpoint())
                    .credentialsProvider(settings.credentialsProvider())
                    .region(LOCAL_REGION)
                    .forcePathStyle(true));
    return builder.build();
  }

  @Bean
  public S3Presigner s3Presigner(S3StorageProperties properties) {
    S3Presigner.Builder builder = S3Presigner.builder();
    String endpoint = properties.endpointOverride();
    if (StringUtils.hasText(properties.publicEndpointOverride())) {
      endpoint = properties.publicEndpointOverride();
    }

    localSettings(properties, endpoint)
        .ifPresent(
            settings ->
                builder
                    .endpointOverride(settings.endpoint())
                    .credentialsProvider(settings.credentialsProvider())
                    .region(LOCAL_REGION)
                    .serviceConfiguration(LOCAL_SERVICE_CONFIGURATION));
    return builder.build();
  }

  private Optional<LocalSettings> localSettings(
      S3StorageProperties properties, String endpointOverride) {
    if (!StringUtils.hasText(endpointOverride)) {
      return Optional.empty();
    }

    URI endpoint = URI.create(endpointOverride);
    AwsBasicCredentials credentials =
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey());
    AwsCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);
    return Optional.of(new LocalSettings(endpoint, credentialsProvider));
  }

  private record LocalSettings(URI endpoint, AwsCredentialsProvider credentialsProvider) {}
}
