package iuh.fit.gateway.presentation.controller;

import iuh.fit.gateway.application.dto.response.base.BaseResponse;
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
        BaseResponse<Void> body = new BaseResponse<Void>(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                null,
                "Service " + serviceName + " is currently unavailable. Please try again later.",
                ""
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
