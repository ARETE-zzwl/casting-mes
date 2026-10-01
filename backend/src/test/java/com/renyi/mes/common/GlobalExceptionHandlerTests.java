package com.renyi.mes.common;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

class GlobalExceptionHandlerTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void preservesFrameworkClientStatusWithoutExposingDetails() {
        var missing = handler.handleUnexpectedFailure(new MissingServletRequestParameterException("private-value", "String"));
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody().error().message()).doesNotContain("private-value");
        var notFound = handler.handleUnexpectedFailure(new NoResourceFoundException(HttpMethod.GET, "private-path", "private-path"));
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void unexpectedFailureHasSupportReferenceWithoutExceptionMessage() {
        var result = handler.handleUnexpectedFailure(new IllegalStateException("secret SQL password"));
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody().error().message()).contains("参考号").doesNotContain("secret SQL password");
        assertThat(result.getHeaders().getFirst("X-Error-Id")).isNotBlank();
    }
}
