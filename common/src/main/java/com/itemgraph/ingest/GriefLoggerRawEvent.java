package com.itemgraph.ingest;

import java.util.Arrays;

/**
 * Represents an uncanonicalized raw event read directly from GriefLogger's
 * items or containers table.
 */
public record GriefLoggerRawEvent(
        long rowid,
        long timestampMs,
        String userName,
        String userUuid,
        String levelName,
        double x,
        double y,
        double z,
        String materialName,
        byte[] rawData,
        int amount,
        int actionId
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GriefLoggerRawEvent that)) return false;
        return rowid == that.rowid &&
                timestampMs == that.timestampMs &&
                Double.compare(that.x, x) == 0 &&
                Double.compare(that.y, y) == 0 &&
                Double.compare(that.z, z) == 0 &&
                amount == that.amount &&
                actionId == that.actionId &&
                java.util.Objects.equals(userName, that.userName) &&
                java.util.Objects.equals(userUuid, that.userUuid) &&
                java.util.Objects.equals(levelName, that.levelName) &&
                java.util.Objects.equals(materialName, that.materialName) &&
                Arrays.equals(rawData, that.rawData);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(rowid, timestampMs, userName, userUuid, levelName, x, y, z, materialName, amount, actionId);
        result = 31 * result + Arrays.hashCode(rawData);
        return result;
    }
}
