package iuh.fit.gateway.controller;

import iuh.fit.gateway.dto.base.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fallback")
public class GatewayFallbackController {

    @GetMapping("/{serviceName}")
    public ResponseEntity<BaseResponse<Void>> getFallback(@PathVariable String serviceName) {
        return buildFallbackResponse(serviceName);
    }

    private ResponseEntity<BaseResponse<Void>> buildFallbackResponse(String serviceName) {
        BaseResponse<Void> body = BaseResponse.<Void>builder()
                .statusCode(HttpStatus.SERVICE_UNAVAILABLE.value())
                .message("Service " + serviceName + " is currently unavailable. Please try again later.")
                .build();

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
