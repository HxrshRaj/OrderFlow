package com.orderflow.order.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptStorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Test
    void disabled_when_no_bucket_configured_and_never_calls_s3() {
        ReceiptStorageService service = new ReceiptStorageService(s3Client, "");

        assertThat(service.enabled()).isFalse();
        service.store("ORD-1", "{}");

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploads_to_the_configured_bucket_with_the_expected_key_and_content_type() {
        ReceiptStorageService service = new ReceiptStorageService(s3Client, "orderflow-receipts");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        assertThat(service.enabled()).isTrue();
        service.store("ORD-42", "{\"orderNumber\":\"ORD-42\"}");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo("orderflow-receipts");
        assertThat(request.key()).isEqualTo("receipts/ORD-42.json");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes())
                .asString().isEqualTo("{\"orderNumber\":\"ORD-42\"}");
    }

    @Test
    void an_s3_failure_is_logged_and_swallowed_not_thrown() {
        ReceiptStorageService service = new ReceiptStorageService(s3Client, "orderflow-receipts");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("bucket does not exist").build());

        service.store("ORD-99", "{}");  // must not throw

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
