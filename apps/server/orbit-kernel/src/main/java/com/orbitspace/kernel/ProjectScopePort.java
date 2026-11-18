package com.orbitspace.kernel;

import java.util.List;

public interface ProjectScopePort {
    record Summary(String projectId, String name) {}

    /** Reject if the project is inactive or does not belong to the already-authorized space. */
    void requireProjectInSpace(String projectId, String spaceId);

    /** Called in the space creation transaction for the optional project-first path. */
    Summary createInitialProject(SpaceAuthorizationPort.Membership owner, String projectName);

    List<Summary> listProjects(SpaceAuthorizationPort.Membership member);
}
