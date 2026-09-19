package iuh.fit.gateway.infrastructure.external.client;

import iuh.fit.gateway.application.dto.response.auth.UserPermissionResponse;
import iuh.fit.gateway.application.dto.response.base.BaseResponse;
import iuh.fit.gateway.infrastructure.config.AuthClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "GOATSPORTS-USER-SERVICE",
        url = "${auth.service.url}",
        path = "/internal/users",
        configuration = AuthClientConfig.class
)
public interface AuthClient {
    @GetMapping("/{userId}/permissions")
    BaseResponse<UserPermissionResponse> getUserPermissions(@PathVariable String userId);
}
