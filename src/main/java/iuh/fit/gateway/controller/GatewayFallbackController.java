package iuh.fit.gateway.controller;

import iuh.fit.gateway.dto.base.BaseResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fallback")
public class GatewayFallbackController {

    @GetMapping("/{serviceName}")
    public ResponseEntity<BaseResponse<Void>> getFallback(@PathVariable String serviceName) {
        return buildFallbackResponse(serviceName);
    }

    @PostMapping("/{serviceName}")
    public ResponseEntity<BaseResponse<Void>> postFallback(@PathVariable String serviceName) {
        return buildFallbackResponse(serviceName);
    }

    @PutMapping("/{serviceName}")
    public ResponseEntity<BaseResponse<Void>> putFallback(@PathVariable String serviceName) {
        return buildFallbackResponse(serviceName);
    }

    @PatchMapping("/{serviceName}")
    public ResponseEntity<BaseResponse<Void>> patchFallback(@PathVariable String serviceName) {
        return buildFallbackResponse(serviceName);
    }

    @DeleteMapping("/{serviceName}")
    public ResponseEntity<BaseResponse<Void>> deleteFallback(@PathVariable String serviceName) {
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
