package iuh.fit.gateway.client;

import iuh.fit.gateway.dto.base.BaseResponse;
import iuh.fit.gateway.dto.base.UserPermissionResponse;
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
