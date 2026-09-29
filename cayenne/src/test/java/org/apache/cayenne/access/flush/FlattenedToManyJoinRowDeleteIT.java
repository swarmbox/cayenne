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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cayenne.Cayenne;
import org.apache.cayenne.DeleteDenyException;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.access.jdbc.PSBatchParameter;
import org.apache.cayenne.access.translator.TranslatedBatch;
import org.apache.cayenne.access.translator.TranslatedStatement;
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.map.DeleteRule;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest1;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest3;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest5;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting the owner of a to-many flattened through a join table, {@code FlattenedTest1.ft5Array} over
 * {@code complexJoins2.toFT5} and {@code FlattenedTest1.ft3OverComplex} over {@code complexJoins.toFT3}, must delete
 * the join rows that refer to the owner, whatever the delete rule, as they cannot outlive it.
 * <p>
 * The tests assert on the SQL, since HSQL's cascading foreign keys would remove a join row the flush left behind.
 */
public class FlattenedToManyJoinRowDeleteIT {

    private static final BoundValuesSQLLogger SQL_LOGGER = new BoundValuesSQLLogger();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_FLATTENED_PROJECT)
            .withExtraModules(binder -> binder.bind(SQLLogger.class).toInstance(SQL_LOGGER));

    private static final String DELETE_FT5_JOIN_ROWS_OF_OWNER = "DELETE FROM COMPLEX_JOIN2 WHERE FT1_FK = ?";
    private static final String DELETE_FT3_JOIN_ROWS_OF_OWNER = "DELETE FROM COMPLEX_JOIN WHERE FT1_FK = ?";
    private static final String DELETE_FT5_JOIN_ROW_OF_TARGET = "DELETE FROM COMPLEX_JOIN2 WHERE FT5_FK = ?";
    private static final String DELETE_OWNER = "DELETE FROM FLATTENED_TEST_1 WHERE FT1_ID = ?";
    private static final String DELETE_FT5 = "DELETE FROM FLATTENED_TEST_5 WHERE FT5_ID = ?";
    private static final String DELETE_FT3 = "DELETE FROM FLATTENED_TEST_3 WHERE FT3_ID = ?";

    // the tables each join table's rows refer to by foreign key
    private static final Map<String, Set<String>> REFERRED_TABLES = Map.of(
            "COMPLEX_JOIN", Set.of("FLATTENED_TEST_1", "FLATTENED_TEST_3"),
            "COMPLEX_JOIN2", Set.of("FLATTENED_TEST_1", "FLATTENED_TEST_5"));

    private DataContext context;

    private TableHelper tComplexJoin;
    private TableHelper tComplexJoin2;

    // the mapped delete rules, which some tests change on the shared runtime's mapping
    private ObjRelationship ft5Array;
    private ObjRelationship toFT1;
    private DeleteRule ft5ArrayRule;
    private DeleteRule toFT1Rule;

    @BeforeEach
    public void setUp() throws SQLException {
        context = env.context();

        ft5Array = env.entityResolver().getObjEntity(FlattenedTest1.class).getRelationship("ft5Array");
        toFT1 = env.entityResolver().getObjEntity(FlattenedTest5.class).getRelationship("toFT1");
        ft5ArrayRule = ft5Array.getDeleteRule();
        toFT1Rule = toFT1.getDeleteRule();

        TableHelper tFlattenedTest1 = env.table("FLATTENED_TEST_1", "FT1_ID", "NAME");
        TableHelper tFlattenedTest3 = env.table("FLATTENED_TEST_3", "FT3_ID", "NAME");
        TableHelper tFlattenedTest5 = env.table("FLATTENED_TEST_5", "FT5_ID", "NAME");
        tComplexJoin = env.table("COMPLEX_JOIN", "PK", "FT1_FK", "FT3_FK");
        tComplexJoin2 = env.table("COMPLEX_JOIN2", "PK", "FT1_FK", "FT5_FK");

        for (int id = 1; id <= 4; id++) {
            tFlattenedTest1.insert(id, "ft1-" + id);
            tFlattenedTest5.insert(id, "ft5-" + id);
        }
        for (int id = 1; id <= 3; id++) {
            tFlattenedTest3.insert(id, "ft3-" + id);
        }

        // ft5Array: FT1 #1 has two rows, #2 and #3 one each, #4 none
        tComplexJoin2.insert(1, 1, 1);
        tComplexJoin2.insert(2, 1, 2);
        tComplexJoin2.insert(3, 2, 3);
        tComplexJoin2.insert(4, 3, 4);

        // ft3OverComplex: FT1 #1 has one row, #2 two, #3 one, #4 none; FT3 #2 is linked to FT1 #2 only
        tComplexJoin.insert(1, 1, 1);
        tComplexJoin.insert(2, 2, 2);
        tComplexJoin.insert(3, 2, 3);
        tComplexJoin.insert(4, 3, 3);
    }

    @AfterEach
    public void restoreDeleteRules() {
        ft5Array.setDeleteRule(ft5ArrayRule);
        toFT1.setDeleteRule(toFT1Rule);
    }

    @Test
    public void deleteOwnerOfSeveralJoinRowsWithoutDeleteRule() throws SQLException {
        ft5Array.setDeleteRule(null);

        List<String> dml = dmlOfCommit(() -> context.deleteObject(ft1(1)));

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT5_JOIN_ROWS_OF_OWNER + " [1]", DELETE_FT3_JOIN_ROWS_OF_OWNER + " [1]"),
                Set.of(DELETE_OWNER + " [1]"));
        assertEquals(Set.of(3, 4), complexJoin2Rows());
        assertEquals(Set.of(2, 3, 4), complexJoinRows());
    }

    @Test
    public void deleteOwnerOfOneJoinRowWithoutDeleteRule() throws SQLException {
        ft5Array.setDeleteRule(null);

        List<String> dml = dmlOfCommit(() -> context.deleteObject(ft1(3)));

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT5_JOIN_ROWS_OF_OWNER + " [3]", DELETE_FT3_JOIN_ROWS_OF_OWNER + " [3]"),
                Set.of(DELETE_OWNER + " [3]"));
        assertEquals(Set.of(1, 2, 3), complexJoin2Rows());
        assertEquals(Set.of(1, 2, 3), complexJoinRows());
    }

    @Test
    public void deleteOwnerOfNoJoinRowsWithoutDeleteRule() throws SQLException {
        ft5Array.setDeleteRule(null);

        // nothing has read the to-manys, so the flush cannot know there are no rows and deletes them by owner
        List<String> dml = dmlOfCommit(() -> context.deleteObject(ft1(4)));

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT5_JOIN_ROWS_OF_OWNER + " [4]", DELETE_FT3_JOIN_ROWS_OF_OWNER + " [4]"),
                Set.of(DELETE_OWNER + " [4]"));
        assertEquals(Set.of(1, 2, 3, 4), complexJoin2Rows());
        assertEquals(Set.of(1, 2, 3, 4), complexJoinRows());
    }

    @Test
    public void deleteOwnerWithResolvedEmptyToManyWithoutDeleteRule() throws SQLException {
        ft5Array.setDeleteRule(null);

        FlattenedTest1 owner = ft1(4);
        assertTrue(owner.getFt5Array().isEmpty());

        // a to-many the context has read and found empty has no rows to delete
        List<String> dml = dmlOfCommit(() -> context.deleteObject(owner));

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT3_JOIN_ROWS_OF_OWNER + " [4]"),
                Set.of(DELETE_OWNER + " [4]"));
    }

    @Test
    public void deleteOwnerWithNullifyRuleDeletesEachJoinRowOnce() throws SQLException {
        ft5Array.setDeleteRule(DeleteRule.NULLIFY);

        // the rule removes each arc, and each removal deletes its join row, leaving none to delete by owner
        List<String> dml = dmlOfCommit(() -> context.deleteObject(ft1(1)));

        assertEquals(DELETE_OWNER + " [1]", dml.getLast());
        assertEquals(2, countStartingWith(dml, "DELETE FROM COMPLEX_JOIN2"), dml.toString());
        assertEquals(0, Collections.frequency(dml, DELETE_FT5_JOIN_ROWS_OF_OWNER + " [1]"), dml.toString());
        assertEquals(1, Collections.frequency(dml, DELETE_FT3_JOIN_ROWS_OF_OWNER + " [1]"), dml.toString());
        assertEquals(Set.of(3, 4), complexJoin2Rows());
        assertEquals(Set.of(2, 3, 4), complexJoinRows());
    }

    @Test
    public void deleteOwnerWithCascadeRule() throws SQLException {
        ft5Array.setDeleteRule(DeleteRule.CASCADE);

        // each cascaded target nullifies its own arc, as mapped, and so deletes its join row
        List<String> dml = dmlOfCommit(() -> context.deleteObject(ft1(1)));

        assertEquals(0, Collections.frequency(dml, DELETE_FT5_JOIN_ROWS_OF_OWNER + " [1]"), dml.toString());
        assertEquals(2, countStartingWith(dml, "DELETE FROM COMPLEX_JOIN2"), dml.toString());
        assertJoinRowsDeletedBeforeWhatTheyReferTo(dml);
        assertEquals(Set.of(DELETE_OWNER + " [1]", DELETE_FT5 + " [1]", DELETE_FT5 + " [2]"), entityDeletes(dml));
        assertEquals(Set.of(3, 4), complexJoin2Rows());
        assertEquals(Set.of(2, 3, 4), complexJoinRows());
    }

    @Test
    public void deleteOwnerWithCascadeRuleWhoseTargetsLeaveTheArc() throws SQLException {
        ft5Array.setDeleteRule(DeleteRule.CASCADE);
        toFT1.setDeleteRule(null);

        List<String> dml = dmlOfCommit(() -> context.deleteObject(ft1(1)));

        assertTrue(dml.contains(DELETE_FT5_JOIN_ROWS_OF_OWNER + " [1]"), dml.toString());
        assertJoinRowsDeletedBeforeWhatTheyReferTo(dml);
        assertEquals(Set.of(DELETE_OWNER + " [1]", DELETE_FT5 + " [1]", DELETE_FT5 + " [2]"), entityDeletes(dml));
        assertEquals(Set.of(3, 4), complexJoin2Rows());
        assertEquals(Set.of(2, 3, 4), complexJoinRows());
    }

    @Test
    public void deleteOwnerWithDenyRuleAndJoinRows() throws SQLException {
        // ft5Array is mapped with DENY
        FlattenedTest1 owner = ft1(1);

        assertThrows(DeleteDenyException.class, () -> context.deleteObject(owner));
        assertEquals(List.of(), dmlOfCommit(() -> { }));
        assertEquals(Set.of(1, 2, 3, 4), complexJoin2Rows());
    }

    @Test
    public void deleteOwnerWithDenyRuleAndNoJoinRows() throws SQLException {
        // ft5Array is mapped with DENY, whose check reads the to-many and finds it empty
        List<String> dml = dmlOfCommit(() -> context.deleteObject(ft1(4)));

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT3_JOIN_ROWS_OF_OWNER + " [4]"),
                Set.of(DELETE_OWNER + " [4]"));
    }

    @Test
    public void deleteTwoOwnersDeletesOnlyTheirJoinRows() throws SQLException {
        ft5Array.setDeleteRule(null);

        List<String> dml = dmlOfCommit(() -> {
            context.deleteObject(ft1(1));
            context.deleteObject(ft1(2));
        });

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT5_JOIN_ROWS_OF_OWNER + " [1]", DELETE_FT5_JOIN_ROWS_OF_OWNER + " [2]",
                        DELETE_FT3_JOIN_ROWS_OF_OWNER + " [1]", DELETE_FT3_JOIN_ROWS_OF_OWNER + " [2]"),
                Set.of(DELETE_OWNER + " [1]", DELETE_OWNER + " [2]"));
        assertEquals(Set.of(4), complexJoin2Rows());
        assertEquals(Set.of(4), complexJoinRows());
    }

    @Test
    public void deleteOwnerAndManyToManyTargetInOneFlush() throws SQLException {
        ft5Array.setDeleteRule(null);

        // FT3 maps no relationship over COMPLEX_JOIN, so only the owner's delete can remove the row between them
        List<String> dml = dmlOfCommit(() -> {
            context.deleteObject(ft1(2));
            context.deleteObject(Cayenne.objectForPK(context, FlattenedTest3.class, 2));
        });

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT5_JOIN_ROWS_OF_OWNER + " [2]", DELETE_FT3_JOIN_ROWS_OF_OWNER + " [2]"),
                Set.of(DELETE_OWNER + " [2]", DELETE_FT3 + " [2]"));
        assertEquals(Set.of(1, 2, 4), complexJoin2Rows());
        assertEquals(Set.of(1, 4), complexJoinRows());
    }

    @Test
    public void deleteOwnerAndItsTargetInOneFlushWithoutDeleteRules() throws SQLException {
        ft5Array.setDeleteRule(null);
        toFT1.setDeleteRule(null);

        // each end deletes the join rows that refer to it, so the row between them is addressed twice
        List<String> dml = dmlOfCommit(() -> {
            context.deleteObject(ft1(2));
            context.deleteObject(Cayenne.objectForPK(context, FlattenedTest5.class, 3));
        });

        assertDeletesInForeignKeyOrder(dml,
                Set.of(DELETE_FT5_JOIN_ROWS_OF_OWNER + " [2]", DELETE_FT3_JOIN_ROWS_OF_OWNER + " [2]",
                        DELETE_FT5_JOIN_ROW_OF_TARGET + " [3]"),
                Set.of(DELETE_OWNER + " [2]", DELETE_FT5 + " [3]"));
        assertEquals(Set.of(1, 2, 4), complexJoin2Rows());
        assertEquals(Set.of(1, 4), complexJoinRows());
    }

    @Test
    public void deleteOwnerAfterLinkingATargetInTheSameFlush() throws SQLException {
        ft5Array.setDeleteRule(null);

        FlattenedTest1 owner = ft1(4);
        List<String> dml = dmlOfCommit(() -> {
            owner.addToFt5Array(Cayenne.objectForPK(context, FlattenedTest5.class, 4));
            context.deleteObject(owner);
        });

        assertEquals(DELETE_OWNER + " [4]", dml.getLast(), dml.toString());
        assertEquals(1, Collections.frequency(dml, DELETE_FT5_JOIN_ROWS_OF_OWNER + " [4]"), dml.toString());
        assertEquals(Set.of(1, 2, 3), complexJoin2Rows());
    }

    private FlattenedTest1 ft1(int id) {
        return Cayenne.objectForPK(context, FlattenedTest1.class, id);
    }

    private Set<Integer> complexJoin2Rows() throws SQLException {
        return primaryKeys(tComplexJoin2);
    }

    private Set<Integer> complexJoinRows() throws SQLException {
        return primaryKeys(tComplexJoin);
    }

    private static Set<Integer> primaryKeys(TableHelper table) throws SQLException {
        Set<Integer> keys = new HashSet<>();
        for (Object[] row : table.selectAll()) {
            keys.add(((Number) row[0]).intValue());
        }
        return keys;
    }

    /**
     * Asserts that the commit wrote exactly the given deletes, each join-row delete ahead of the deletes of the
     * tables its rows refer to.
     */
    private static void assertDeletesInForeignKeyOrder(List<String> dml, Set<String> joinRowDeletes,
                                                       Set<String> entityDeletes) {
        Set<String> allDeletes = new HashSet<>(joinRowDeletes);
        allDeletes.addAll(entityDeletes);
        assertEquals(allDeletes.size(), dml.size(), dml.toString());
        assertEquals(allDeletes, Set.copyOf(dml), dml.toString());
        assertJoinRowsDeletedBeforeWhatTheyReferTo(dml);
    }

    private static void assertJoinRowsDeletedBeforeWhatTheyReferTo(List<String> dml) {
        for (int joinRowDelete = 0; joinRowDelete < dml.size(); joinRowDelete++) {
            Set<String> referredTables = REFERRED_TABLES.getOrDefault(tableOf(dml.get(joinRowDelete)), Set.of());
            for (int earlier = 0; earlier < joinRowDelete; earlier++) {
                boolean refersToEarlierDelete = referredTables.contains(tableOf(dml.get(earlier)));
                assertTrue(!refersToEarlierDelete, "a join row is deleted after what it refers to: " + dml);
            }
        }
    }

    private static String tableOf(String statement) {
        // "DELETE FROM <table> WHERE ..." or "UPDATE <table> SET ..."
        String[] words = statement.split(" ");
        return statement.startsWith("DELETE") ? words[2] : words[1];
    }

    private static Set<String> entityDeletes(List<String> dml) {
        Set<String> deletes = new HashSet<>();
        for (String statement : dml) {
            if (statement.startsWith("DELETE FROM FLATTENED_TEST_")) {
                deletes.add(statement);
            }
        }
        return deletes;
    }

    private static int countStartingWith(List<String> dml, String prefix) {
        int count = 0;
        for (String statement : dml) {
            if (statement.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Runs the change, commits it and returns each row the commit wrote, in order, as its SQL followed by the values
     * bound to it.
     */
    private List<String> dmlOfCommit(Runnable change) {
        SQL_LOGGER.rows.clear();
        change.run();
        context.commitChanges();
        return List.copyOf(SQL_LOGGER.rows);
    }

    /**
     * Unlike the shared {@code org.apache.cayenne.unit.RecordingSQLLogger}, records each batch row's SQL together with
     * its bound values, and ignores failed statements.
     */
    private static class BoundValuesSQLLogger implements SQLLogger {

        final List<String> rows = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void logSelect(TranslatedStatement statement, int rowCount, long durationMillis) {
        }

        @Override
        public void logUpdate(TranslatedStatement statement, int rowCount,
                              List<? extends Map<String, ?>> generatedKeys, long durationMillis) {
            if (!(statement instanceof TranslatedBatch batch) || batch.bindings().length == 0) {
                rows.add(statement.sql());
                return;
            }
            int batchSize = batch.bindings()[0].values().length;
            for (int row = 0; row < batchSize; row++) {
                rows.add(statement.sql() + " " + boundValues(batch, row));
            }
        }

        private static String boundValues(TranslatedBatch batch, int row) {
            Object[] values = new Object[batch.bindings().length];
            for (int i = 0; i < values.length; i++) {
                PSBatchParameter binding = batch.bindings()[i];
                values[i] = binding.getValue(row);
            }
            return Arrays.toString(values);
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
