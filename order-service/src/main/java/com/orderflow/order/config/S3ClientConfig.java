package com.orderflow.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Real {@link S3Client}, built with the AWS SDK's default credential/region provider chain (env
 * vars, {@code ~/.aws/credentials}, an instance profile — whatever the environment offers). No
 * credentials are configured here, so this picks up real AWS access the same way the AWS CLI
 * would, with no code change between local dev and a real deployment.
 *
 * <p>Tests never exercise this bean: {@code ReceiptStorageServiceTest} constructs
 * {@link com.orderflow.order.storage.ReceiptStorageService} directly with a Mockito-mocked
 * {@link S3Client}, so building the real client never runs in CI and never requires AWS
 * credentials to be present.
 */
@Configuration
public class S3ClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client(@Value("${orderflow.receipts.region:ap-south-1}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}
