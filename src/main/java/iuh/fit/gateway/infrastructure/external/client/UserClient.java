package iuh.fit.gateway.infrastructure.external.client;

import iuh.fit.gateway.application.dto.response.auth.UserPermissionResponse;
import iuh.fit.gateway.application.dto.response.base.BaseResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
        name = "GOATSPORTS-AUTH-SERVICE",
        url = "${auth.service.url}",
        path = "/internal/users"
)
public interface UserClient {
    @GetMapping("/{userId}/permissions")
    BaseResponse<UserPermissionResponse> getUserPermissions(@PathVariable String userId);
}
