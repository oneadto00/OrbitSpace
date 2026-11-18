package com.orbitspace.kernel;

public enum SpaceRole {
    OWNER(3), ADMIN(2), MEMBER(1), VIEWER(0);

    private final int rank;

    SpaceRole(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(SpaceRole minimum) {
        return rank >= minimum.rank;
    }
}
