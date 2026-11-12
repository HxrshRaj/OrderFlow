package com.orderflow.order.config;

import com.orderflow.order.web.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.http.HttpStatus;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdPropagationInterceptorTest {

    private final RequestIdPropagationInterceptor interceptor = new RequestIdPropagationInterceptor();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void forwards_the_current_request_id_as_a_header() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://inventory/x"));

        interceptor.intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertThat(request.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("req-42");
    }

    @Test
    void sends_no_header_when_there_is_no_current_request_id() throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://inventory/x"));

        interceptor.intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertThat(request.getHeaders()).doesNotContainKey(RequestIdFilter.HEADER);
    }
}
