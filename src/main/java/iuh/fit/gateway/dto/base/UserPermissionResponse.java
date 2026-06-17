package iuh.fit.gateway.dto.base;

import lombok.*;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserPermissionResponse {
    private String email;
    private String role;
    private Set<String> permissions;
}
