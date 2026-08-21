/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/

package org.apache.cayenne.access.flush;

import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.access.translator.TranslatedStatement;
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.relationships.E1;
import org.apache.cayenne.testdo.relationships.E2;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting rows of two entities that reference each other orders the deletes by the rows' committed foreign keys,
 * which the flush reads with at most one query per entity and thousand rows when they aren't cached.
 */
public class RowDependencySortSnapshotIT {

    private static final int ROWS = 10;
    private static final int ROWS_PER_READ = 1000;
    private static final int MANY_ROWS = 10_001;
    private static final SelectCounter SELECTS = new SelectCounter();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_PROJECT)
            .withExtraModules(binder -> binder.bind(SQLLogger.class).toInstance(SELECTS));

    private DataContext context;
    private TableHelper tE1;
    private TableHelper tE2;

    @BeforeEach
    public void setUp() {
        context = env.context();
        tE1 = env.table("CYCLE_E1").setColumns("id", "e2_id", "text")
                .setColumnTypes(Types.INTEGER, Types.INTEGER, Types.VARCHAR);
        tE2 = env.table("CYCLE_E2").setColumns("id", "e1_id", "text")
                .setColumnTypes(Types.INTEGER, Types.INTEGER, Types.VARCHAR);
    }

    @AfterEach
    public void breakCycle() throws SQLException {
        tE1.update().set("e2_id", null, Types.INTEGER).execute();
        tE2.update().set("e1_id", null, Types.INTEGER).execute();
        tE1.deleteAll();
        tE2.deleteAll();
    }

    @Test
    public void deletesOfUncachedRowsReadAtMostOneQueryPerEntity() throws SQLException {
        createRows(false, false);

        assertTrue(selectsOfDeleteAll(false) <= 2, () -> "SELECTs: " + SELECTS.count.get());
        assertEquals(0, tE1.getRowCount() + tE2.getRowCount());
    }

    @Test
    public void deletesOfUncachedRowsReferencingTheOtherEntity() throws SQLException {
        createRows(true, false);

        assertTrue(selectsOfDeleteAll(false) <= 2, () -> "SELECTs: " + SELECTS.count.get());
        assertEquals(0, tE1.getRowCount() + tE2.getRowCount());
    }

    @Test
    public void deletesOfUncachedRowsReferencedByTheOtherEntity() throws SQLException {
        createRows(false, true);

        assertTrue(selectsOfDeleteAll(true) <= 2, () -> "SELECTs: " + SELECTS.count.get());
        assertEquals(0, tE1.getRowCount() + tE2.getRowCount());
    }

    /**
     * A run of more rows than one query reads is read in chunks, none of them a query too large for the database to
     * prepare, so the deletes are still ordered by the rows' foreign keys. The rows of an entity with a single-column
     * key are read with an IN list.
     */
    @Test
    public void deletesOfManyUncachedRowsReadTheirRowsInChunks() throws SQLException {
        E2 e2 = context.newObject(E2.class);
        e2.setText("e2");
        for (int i = 0; i < MANY_ROWS; i++) {
            E1 e1 = context.newObject(E1.class);
            e1.setText("e1-" + i);
            e1.setE2(e2);
        }
        context.commitChanges();

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream err = System.err;
        System.setErr(new PrintStream(log, true));
        int selects;
        try {
            selects = selectsOfDeleteAll(false);
        } finally {
            System.setErr(err);
        }

        int e1Reads = (MANY_ROWS + ROWS_PER_READ - 1) / ROWS_PER_READ;
        assertEquals(e1Reads + 1, selects);
        long readsWithoutInList = SELECTS.sql.stream().filter(sql -> !sql.contains(" IN (")).count();
        assertEquals(0, readsWithoutInList, () -> "First SELECT: " + SELECTS.sql.getFirst().substring(0, 120));
        assertFalse(log.toString().contains("Failed to sort operations by row dependency"));
        assertEquals(0, tE1.getRowCount() + tE2.getRowCount());
    }

    /**
     * Creates {@link #ROWS} rows of each entity, row i of one optionally referencing row i of the other.
     */
    private void createRows(boolean e1ReferencesE2, boolean e2ReferencesE1) {
        List<E1> e1s = new ArrayList<>();
        List<E2> e2s = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            E1 e1 = context.newObject(E1.class);
            e1.setText("e1-" + i);
            e1s.add(e1);
            E2 e2 = context.newObject(E2.class);
            e2.setText("e2-" + i);
            e2s.add(e2);
        }
        context.commitChanges();

        for (int i = 0; i < ROWS; i++) {
            if (e1ReferencesE2) {
                e1s.get(i).setE2(e2s.get(i));
            }
            if (e2ReferencesE1) {
                e2s.get(i).setE1(e1s.get(i));
            }
        }
        context.commitChanges();
    }

    /**
     * Deletes every row from a fresh context with an empty snapshot cache, returning the number of SELECTs the commit
     * ran. The objects of the entity that references the other are deleted first, as the DENY rules of the reverse
     * to-manys veto deleting a referenced object first.
     */
    private int selectsOfDeleteAll(boolean e2sFirst) {
        DataContext deleting = (DataContext) env.runtime().newContext();
        List<E1> e1s = ObjectSelect.query(E1.class).select(deleting);
        List<E2> e2s = ObjectSelect.query(E2.class).select(deleting);
        if (e2sFirst) {
            deleting.deleteObjects(e2s);
            deleting.deleteObjects(e1s);
        } else {
            deleting.deleteObjects(e1s);
            deleting.deleteObjects(e2s);
        }
        env.runtime().getDataDomain().getSharedSnapshotCache().clear();

        SELECTS.reset();
        deleting.commitChanges();
        return SELECTS.count.get();
    }

    private static class SelectCounter implements SQLLogger {

        final AtomicInteger count = new AtomicInteger();
        final List<String> sql = new CopyOnWriteArrayList<>();

        void reset() {
            count.set(0);
            sql.clear();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void logSelect(TranslatedStatement statement, int rowCount, long durationMillis) {
            count.incrementAndGet();
            sql.add(statement.sql());
        }

        @Override
        public void logUpdate(TranslatedStatement statement, int rowCount, List<? extends Map<String, ?>> generatedKeys,
                              long durationMillis) {
        }

        @Override
        public void logQueryError(TranslatedStatement statement, Throwable error, long durationMillis) {
        }

        @Override
        public void logAlsoSelect(int rowCount) {
        }

        @Override
        public void logAlsoUpdate(int rowCount) {
        }

        @Override
        public void logTransactionStart() {
        }

        @Override
        public void logTransactionCommit() {
        }

        @Override
        public void logTransactionRollback() {
        }

        @Override
        public void logMessage(String message) {
        }
    }
}
