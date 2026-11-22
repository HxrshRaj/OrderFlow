package com.orderflow.order.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;

/**
 * Stores the order-confirmation receipt in S3, one object per order, keyed by order number.
 *
 * <p>Deliberately decoupled from how the receipt body is built ({@link ReceiptStorageListener}
 * builds it) and from whether S3 is even configured: {@code orderflow.receipts.bucket} is unset
 * in local/dev profiles, and {@link #store} is a no-op logging that fact rather than failing the
 * order-confirmation transaction over a missing bucket name. A receipt is a nice-to-have audit
 * artifact, not something that should be able to break order confirmation.
 *
 * <p>{@code S3Client} is constructed with the AWS SDK's default credential/region chain, so it
 * picks up real credentials in any environment that has them (env vars, instance profile,
 * {@code ~/.aws/credentials}) with no code change — the unit test
 * ({@code ReceiptStorageServiceTest}) injects a mocked {@link S3Client} instead, so no AWS
 * account or network access is needed to verify this class.
 */
@Service
public class ReceiptStorageService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptStorageService.class);

    private final S3Client s3Client;
    private final String bucket;

    public ReceiptStorageService(S3Client s3Client,
                                  @Value("${orderflow.receipts.bucket:}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    /** True once a bucket is actually configured; false in profiles that don't set one. */
    public boolean enabled() {
        return bucket != null && !bucket.isBlank();
    }

    /**
     * Uploads {@code receiptBody} as {@code receipts/<orderNumber>.json}. Never throws: an S3
     * outage should not be able to fail order confirmation, so a failed upload is logged and
     * swallowed — this is a durability nice-to-have for an already-committed order, not a step
     * the order's own correctness depends on.
     */
    public void store(String orderNumber, String receiptBody) {
        if (!enabled()) {
            log.debug("orderflow.receipts.bucket not configured; skipping receipt upload for order {}", orderNumber);
            return;
        }
        String key = "receipts/%s.json".formatted(orderNumber);
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromBytes(receiptBody.getBytes(StandardCharsets.UTF_8)));
            log.info("uploaded receipt for order {} to s3://{}/{}", orderNumber, bucket, key);
        } catch (S3Exception e) {
            log.warn("failed to upload receipt for order {} to s3://{}/{}: {}", orderNumber, bucket, key, e.getMessage());
        }
    }
}
