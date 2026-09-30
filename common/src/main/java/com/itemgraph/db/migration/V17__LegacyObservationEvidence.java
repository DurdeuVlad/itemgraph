package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** Creates the immutable archive used when upgrading databases through legacy reset migrations. */
public final class V17__LegacyObservationEvidence implements SchemaMigration {

    @Override
    public int getVersion() {
        return 17;
    }

    @Override
    public String getDescription() {
        return "Retain immutable evidence copied by legacy observation reset migrations";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        LegacyObservationArchive.ensureArchiveTable(connection);
    }
}
