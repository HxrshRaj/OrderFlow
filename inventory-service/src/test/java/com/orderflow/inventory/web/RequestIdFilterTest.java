package com.orderflow.inventory.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String runAndCaptureMdc(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        };
        filter.doFilter(request, response, chain);
        return seen.get();
    }

    @Test
    void generates_an_id_when_none_is_sent_and_echoes_it() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = runAndCaptureMdc(new MockHttpServletRequest(), response);

        assertThat(seen).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seen);
    }

    @Test
    void reuses_a_well_formed_incoming_id() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "order-123.abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = runAndCaptureMdc(request, response);

        assertThat(seen).isEqualTo("order-123.abc");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("order-123.abc");
    }

    @Test
    void replaces_an_unsafe_incoming_id_to_prevent_log_injection() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "bad id\nFAKE LOG LINE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = runAndCaptureMdc(request, response);

        assertThat(seen).doesNotContain("\n").doesNotContain(" ");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seen);
    }

    @Test
    void clears_the_mdc_after_the_request() throws Exception {
        runAndCaptureMdc(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
