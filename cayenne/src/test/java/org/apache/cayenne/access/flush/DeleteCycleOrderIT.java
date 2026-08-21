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

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.access.translator.TranslatedStatement;
import org.apache.cayenne.exp.ExpressionFactory;
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Two tables reference each other, and the first also references itself. A parent row, its child row and the row of
 * the other table that the parent references are deleted in one commit, in every order of the delete calls. The
 * objects of the first table have a flattened row whose primary key column is named like the column the foreign keys
 * of the first table reference.
 */
public class DeleteCycleOrderIT {

    private static final int[][] CALL_ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    private static final DeletedRows DELETED_ROWS = new DeletedRows();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.DELETE_CYCLE_PROJECT)
            .withExtraModules(b -> b.bind(SQLLogger.class).toInstance(DELETED_ROWS));

    private TableHelper tA;
    private TableHelper tASub;
    private TableHelper tB;
    private TableHelper tE;
    private TableHelper tEExt;
    private TableHelper tF;

    @BeforeEach
    public void setUp() {
        tA = env.table("DEL_CYCLE_A").setColumns("ID", "DISC", "PARENT_ID", "B_REF")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.INTEGER);
        tASub = env.table("DEL_CYCLE_A_SUB", "ID", "EXTRA");
        tB = env.table("DEL_CYCLE_B").setColumns("B_ID", "A_REF").setColumnTypes(Types.INTEGER, Types.INTEGER);
        tE = env.table("DEL_CYCLE_E").setColumns("ID", "PARENT_ID", "F_REF")
                .setColumnTypes(Types.INTEGER, Types.INTEGER, Types.INTEGER);
        tEExt = env.table("DEL_CYCLE_E_EXT", "ID", "NOTE");
        tF = env.table("DEL_CYCLE_F").setColumns("F_ID", "E_REF").setColumnTypes(Types.INTEGER, Types.INTEGER);
    }

    @AfterEach
    public void cleanUp() throws SQLException {
        deleteAll();
    }

    /**
     * The flattened row is the sub-table row of a vertical-inheritance sub-entity.
     */
    @Test
    public void subEntityRowsInEveryCallOrder() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int[] callOrder : CALL_ORDERS) {
            deleteAll();
            tB.insert(1, null);
            tA.insert(1, "S", null, 1);
            tA.insert(2, "S", 1, null);
            tASub.insert(1, "x");
            tASub.insert(2, "y");

            ObjectContext context = env.runtime().newContext();
            List<Persistent> objects = List.of(
                    object(context, "DelCycleSub", "ID", 1),
                    object(context, "DelCycleSub", "ID", 2),
                    object(context, "DelCycleB", "B_ID", 1));
            deleteAndCommit(context, objects, callOrder, "DEL_CYCLE_A", failures);
        }

        assertEquals(List.of(), failures);
        assertEquals(0, tA.getRowCount());
        assertEquals(0, tASub.getRowCount());
    }

    /**
     * The flattened row is the row of a flattened attribute in a one-to-one table.
     */
    @Test
    public void flattenedAttributeRowsInEveryCallOrder() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int[] callOrder : CALL_ORDERS) {
            deleteAll();
            tF.insert(1, null);
            tE.insert(1, null, 1);
            tE.insert(2, 1, null);
            tEExt.insert(1, "x");
            tEExt.insert(2, "y");

            ObjectContext context = env.runtime().newContext();
            List<Persistent> objects = List.of(
                    object(context, "DelCycleE", "ID", 1),
                    object(context, "DelCycleE", "ID", 2),
                    object(context, "DelCycleF", "F_ID", 1));
            deleteAndCommit(context, objects, callOrder, "DEL_CYCLE_E", failures);
        }

        assertEquals(List.of(), failures);
        assertEquals(0, tE.getRowCount());
        assertEquals(0, tEExt.getRowCount());
    }

    private static Persistent object(ObjectContext context, String entityName, String pkColumn, int id) {
        return ObjectSelect.query(Persistent.class, entityName)
                .where(ExpressionFactory.matchDbExp(pkColumn, id))
                .selectOne(context);
    }

    /**
     * Deletes the parent, the child and the other table's row in the call order, and records a failure unless the
     * commit deleted both rows of the self-referencing table with its own statements. A child row deleted after its
     * parent violates the parent foreign key, or, where the foreign key cascades, is already gone.
     */
    private static void deleteAndCommit(
            ObjectContext context,
            List<Persistent> objects,
            int[] callOrder,
            String selfReferencingTable,
            List<String> failures) {
        List<String> deleted = new ArrayList<>();
        for (int i : callOrder) {
            Persistent object = objects.get(i);
            context.deleteObject(object);
            deleted.add(object.getObjectId().toString());
        }

        DELETED_ROWS.clear();
        try {
            context.commitChanges();
        } catch (RuntimeException e) {
            failures.add(deleted + ": " + e.getMessage());
            return;
        }

        int deletedRows = DELETED_ROWS.of(selfReferencingTable);
        if (deletedRows != 2) {
            failures.add(deleted + ": " + deletedRows + " of 2 rows deleted from " + selfReferencingTable);
        }
    }

    private void deleteAll() throws SQLException {
        tB.update().set("A_REF", null, Types.INTEGER).execute();
        tA.update().set("PARENT_ID", null, Types.INTEGER).set("B_REF", null, Types.INTEGER).execute();
        tASub.deleteAll();
        tA.deleteAll();
        tB.deleteAll();

        tF.update().set("E_REF", null, Types.INTEGER).execute();
        tE.update().set("PARENT_ID", null, Types.INTEGER).set("F_REF", null, Types.INTEGER).execute();
        tEExt.deleteAll();
        tE.deleteAll();
        tF.deleteAll();
    }

    /**
     * Counts the rows the DELETE statements of each table deleted.
     */
    private static class DeletedRows implements SQLLogger {

        private static final Pattern DELETE = Pattern.compile("^\\s*DELETE\\s+FROM\\s+[\"`\\[]?(\\w+)",
                Pattern.CASE_INSENSITIVE);

        private final Map<String, Integer> rowsByTable = new ConcurrentHashMap<>();
        private volatile String lastDeleteTable;

        void clear() {
            rowsByTable.clear();
        }

        int of(String table) {
            return rowsByTable.getOrDefault(table.toUpperCase(), 0);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void logUpdate(
                TranslatedStatement statement,
                int rowCount,
                List<? extends Map<String, ?>> generatedKeys,
                long durationMillis) {
            Matcher matcher = DELETE.matcher(statement.sql());
            lastDeleteTable = matcher.find() ? matcher.group(1).toUpperCase() : null;
            logAlsoUpdate(rowCount);
        }

        @Override
        public void logAlsoUpdate(int rowCount) {
            if (lastDeleteTable != null) {
                rowsByTable.merge(lastDeleteTable, rowCount, Integer::sum);
            }
        }

        @Override
        public void logSelect(TranslatedStatement statement, int rowCount, long durationMillis) {
        }

        @Override
        public void logQueryError(TranslatedStatement statement, Throwable error, long durationMillis) {
        }

        @Override
        public void logAlsoSelect(int rowCount) {
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
