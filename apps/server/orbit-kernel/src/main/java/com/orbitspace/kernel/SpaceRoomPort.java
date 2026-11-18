package com.orbitspace.kernel;

/** Chat owns room creation and message sequence allocation across the space/chat boundary. */
public interface SpaceRoomPort {
    /** Create the unique room in the space creation transaction. Repeated calls return its stable ID. */
    String ensureMainRoom(String spaceId);

    record RoomPosition(String roomId, long maxMessageSeq) {}

    /** Read the authoritative main room and join baseline inside the calling membership transaction. */
    RoomPosition currentPosition(String spaceId);
}
