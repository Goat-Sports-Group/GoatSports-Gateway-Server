package iuh.fit.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import iuh.fit.gateway.dto.base.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class GlobalErrorWebExceptionHandler implements WebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalErrorWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatusCode status;
        Object messageBody;

        if (ex instanceof ResponseStatusException rse) {
            status = rse.getStatusCode();
            messageBody = rse.getReason() != null ? rse.getReason() : rse.getMessage();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            messageBody = ex.getMessage();
        }

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.setStatusCode(status);

        BaseResponse<Object> baseResponse = new BaseResponse<>();
        baseResponse.setStatusCode(status.value());
        baseResponse.setData(null);
        baseResponse.setMessage(messageBody);
        baseResponse.setError(ex.getClass().getSimpleName());

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(baseResponse);
        } catch (Exception e) {
            log.error("Failed to serialize error response", e);
            bytes = ("{\"statusCode\":" + status.value() + ",\"error\":\"serialization_error\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}

