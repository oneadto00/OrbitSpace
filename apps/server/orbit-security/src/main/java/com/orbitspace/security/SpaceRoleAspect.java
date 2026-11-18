package com.orbitspace.security;

import com.orbitspace.kernel.*;
import java.util.Map;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

@Aspect
public class SpaceRoleAspect {
    private final SpaceAccess access;
    public SpaceRoleAspect(SpaceAccess access) { this.access = access; }

    @Before("@annotation(role)")
    public void enforce(RequireSpaceRole role) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new DomainException(KernelErrorCode.AUTH_UNAUTHORIZED);
        var req = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var attributes = req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Map<?, ?> path = attributes instanceof Map<?, ?> map ? map : Map.of();
        String space = (String) path.get("spaceId");
        String project = (String) path.get("projectId");
        if (auth.getPrincipal() instanceof DevicePrincipal device) {
            if (space != null && !device.spaceId().equals(space) || !device.projectId().equals(project)
                    || !device.scopes().contains(role.deviceScope())) throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
            access.member(device.userId(), device.spaceId(), device.membershipEpoch(), role.value());
            access.project(device.spaceId(), project);
        } else if (auth.getPrincipal() instanceof AuthenticatedPrincipal account && space != null) {
            long epoch;
            try { epoch = Long.parseLong(req.getHeader("X-Membership-Epoch")); }
            catch (NumberFormatException e) { throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID); }
            access.member(account.userId(), space, epoch, role.value());
            if (project != null) access.project(space, project);
        } else throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
    }
}
