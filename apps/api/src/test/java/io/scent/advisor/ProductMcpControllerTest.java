package io.scent.advisor;

import io.scent.account.AttemptLimiter;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ProductMcpControllerTest {
    @Test void missingCredentialDisablesTheEndpointAndOriginIsNeverAccepted() throws Exception {
        var controller = new ProductMcpController(null, new AttemptLimiter(), new ObjectMapper(), "");
        var request = new MockHttpServletRequest("POST", ProductMcpController.PATH);
        assertEquals(503, controller.handle(request).getStatusCode().value());
        request.addHeader("Origin", "http://127.0.0.1");
        assertEquals(403, controller.handle(request).getStatusCode().value());
    }

    @Test void configuredCredentialMustBeAnAbsolutePrivateFileWithStrongFormat() throws Exception {
        assertThrows(IllegalStateException.class, () -> new ProductMcpController(null, new AttemptLimiter(), new ObjectMapper(), "relative.key"));
        Path file = Files.createTempFile("scent-mcp-invalid-", ".key");
        try {
            Files.writeString(file, "short");
            assertThrows(IllegalStateException.class, () -> new ProductMcpController(null, new AttemptLimiter(), new ObjectMapper(), file.toString()));
        } finally { Files.delete(file); }
    }
}
