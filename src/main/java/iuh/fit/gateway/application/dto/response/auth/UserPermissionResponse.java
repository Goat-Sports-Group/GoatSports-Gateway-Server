package iuh.fit.gateway.application.dto.response.auth;
import java.util.Set;

public record UserPermissionResponse (
    String email,
    String role,
    Set<String> permissions
) {}