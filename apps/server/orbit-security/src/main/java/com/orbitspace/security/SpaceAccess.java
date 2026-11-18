package com.orbitspace.security;

import com.orbitspace.kernel.*;
import org.springframework.beans.factory.ObjectProvider;

public class SpaceAccess {
    private final ObjectProvider<SpaceAuthorizationPort> spaces;
    private final ObjectProvider<ProjectScopePort> projects;

    public SpaceAccess(ObjectProvider<SpaceAuthorizationPort> spaces, ObjectProvider<ProjectScopePort> projects) {
        this.spaces = spaces;
        this.projects = projects;
    }

    public SpaceAuthorizationPort.Membership member(String user, String space, long epoch, SpaceRole role) {
        var port = spaces.getIfAvailable();
        if (port == null) throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        var member = port.requireMembership(space, user, epoch, role);
        if (member == null || !user.equals(member.userId()) || !space.equals(member.spaceId())
                || member.membershipEpoch() != epoch || !member.role().atLeast(role)) {
            throw new DomainException(KernelErrorCode.MEMBERSHIP_EPOCH_INVALID);
        }
        return member;
    }

    public void project(String space, String project) {
        var port = projects.getIfAvailable();
        if (port == null) throw new DomainException(KernelErrorCode.AUTH_FORBIDDEN);
        port.requireProjectInSpace(project, space);
    }
}
