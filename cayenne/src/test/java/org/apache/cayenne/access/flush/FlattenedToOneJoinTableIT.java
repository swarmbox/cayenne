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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.cayenne.Cayenne;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.map.DeleteRule;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest1;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest5;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.unit.RecordingSQLLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A to-one flattened through a join table, {@code FlattenedTest5.toFT1} over {@code complexJoin2.toFT1}, must be
 * written as a change to its join-table row whichever end the change is made from and whether or not the flush
 * knows that row's key.
 */
public class FlattenedToOneJoinTableIT {

    private static final RecordingSQLLogger SQL_LOGGER = new RecordingSQLLogger();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_FLATTENED_PROJECT)
            .withExtraModules(binder -> binder.bind(SQLLogger.class).toInstance(SQL_LOGGER));

    private static final String DELETE_JOIN_ROW_BY_OWNER = "DELETE FROM COMPLEX_JOIN2 WHERE FT5_FK = ?";
    private static final String DELETE_OWNER = "DELETE FROM FLATTENED_TEST_5 WHERE FT5_ID = ?";

    private DataContext context;

    private TableHelper tFlattenedTest1;
    private TableHelper tFlattenedTest5;
    private TableHelper tComplexJoin2;

    // the mapped delete rules, which some tests change on the shared runtime's mapping
    private ObjRelationship toFT1;
    private ObjRelationship ft5Array;
    private DeleteRule toFT1Rule;
    private DeleteRule ft5ArrayRule;

    @BeforeEach
    public void setUp() {
        context = env.context();

        tFlattenedTest1 = env.table("FLATTENED_TEST_1", "FT1_ID", "NAME");
        tFlattenedTest5 = env.table("FLATTENED_TEST_5", "FT5_ID", "NAME");
        tComplexJoin2 = env.table("COMPLEX_JOIN2", "PK", "FT1_FK", "FT5_FK");

        toFT1 = env.entityResolver().getObjEntity(FlattenedTest5.class).getRelationship("toFT1");
        ft5Array = env.entityResolver().getObjEntity(FlattenedTest1.class).getRelationship("ft5Array");
        toFT1Rule = toFT1.getDeleteRule();
        ft5ArrayRule = ft5Array.getDeleteRule();
    }

    @AfterEach
    public void restoreDeleteRules() {
        toFT1.setDeleteRule(toFT1Rule);
        ft5Array.setDeleteRule(ft5ArrayRule);
    }

    private void createJoinRowDataSet() throws SQLException {
        tFlattenedTest1.insert(1, "ft1-1");
        tFlattenedTest1.insert(2, "ft1-2");
        tFlattenedTest1.insert(3, "ft1-3");
        tFlattenedTest5.insert(1, "ft5-1");
        tFlattenedTest5.insert(2, "ft5-2");
        tComplexJoin2.insert(1, 1, 1);
        tComplexJoin2.insert(2, 2, 2);
    }

    @Test
    public void repointFetchedOwner() throws SQLException {
        createJoinRowDataSet();

        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
        context.commitChanges();

        assertEquals(Set.of(new JoinRow(1, 3), new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void repointHollowOwner() throws SQLException {
        createJoinRowDataSet();

        ObjectContext fetchingContext = env.runtime().newContext();
        FlattenedTest5 fetchedOwner = Cayenne.objectForPK(fetchingContext, FlattenedTest5.class, 1);
        FlattenedTest1 fetchedTarget = Cayenne.objectForPK(fetchingContext, FlattenedTest1.class, 3);

        FlattenedTest5 owner = context.localObject(fetchedOwner);
        owner.setToFT1(context.localObject(fetchedTarget));
        context.commitChanges();

        assertEquals(Set.of(new JoinRow(1, 3), new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void repointFromTargetEnd() throws SQLException {
        createJoinRowDataSet();

        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        Cayenne.objectForPK(context, FlattenedTest1.class, 3).addToFt5Array(owner);
        context.commitChanges();

        assertEquals(Set.of(new JoinRow(1, 3), new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void clearFetchedOwner() throws SQLException {
        createJoinRowDataSet();

        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        owner.setToFT1(null);
        context.commitChanges();

        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void deleteOwnerModifiedFirst() throws SQLException {
        createJoinRowDataSet();

        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        owner.setName("ft5-1-renamed");
        context.deleteObject(owner);
        context.commitChanges();

        // HSQL's generated foreign keys cascade on delete, so only backends without that can fail here
        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
        assertEquals(1, tFlattenedTest5.getRowCount());
        assertEquals(2, ((Number) tFlattenedTest5.select()[0]).intValue());
    }

    // The owner-delete cases below assert on the SQL, since HSQL's cascading foreign keys would remove a join row
    // the flush left behind. A relationship mapped without a delete rule leaves the arc alone, so only the owner's
    // own delete can remove the row, and a fetch does not read that row's key.

    @Test
    public void deleteFetchedOwnerWithoutDeleteRule() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(null);

        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        assertEquals(List.of(DELETE_JOIN_ROW_BY_OWNER, DELETE_OWNER),
                dmlOfCommit(() -> context.deleteObject(owner)));
        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void deleteJointFetchedOwnerWithoutDeleteRule() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(null);

        FlattenedTest5 owner = ObjectSelect.query(FlattenedTest5.class)
                .where(FlattenedTest5.NAME.eq("ft5-1"))
                .prefetch(FlattenedTest5.TO_FT1.joint())
                .selectOne(context);
        assertEquals(List.of(DELETE_JOIN_ROW_BY_OWNER, DELETE_OWNER),
                dmlOfCommit(() -> context.deleteObject(owner)));
        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void deleteDisjointFetchedOwnerWithoutDeleteRule() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(null);

        FlattenedTest5 owner = ObjectSelect.query(FlattenedTest5.class)
                .where(FlattenedTest5.NAME.eq("ft5-1"))
                .prefetch(FlattenedTest5.TO_FT1.disjoint())
                .selectOne(context);
        assertEquals(List.of(DELETE_JOIN_ROW_BY_OWNER, DELETE_OWNER),
                dmlOfCommit(() -> context.deleteObject(owner)));
        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void deleteFetchedOwnerWithCascadeRule() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(DeleteRule.CASCADE);
        // the mapped DENY on the reverse would veto the cascaded delete of the target
        ft5Array.setDeleteRule(null);

        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        List<String> dml = dmlOfCommit(() -> context.deleteObject(owner));
        // the cascaded target deletes the rows of its own join tables that refer to it too
        Set<String> joinRowDeletes = Set.of(DELETE_JOIN_ROW_BY_OWNER,
                "DELETE FROM COMPLEX_JOIN2 WHERE FT1_FK = ?", "DELETE FROM COMPLEX_JOIN WHERE FT1_FK = ?");
        Set<String> rowDeletes = Set.of(DELETE_OWNER, "DELETE FROM FLATTENED_TEST_1 WHERE FT1_ID = ?");
        assertEquals(joinRowDeletes, Set.copyOf(dml.subList(0, joinRowDeletes.size())), dml.toString());
        assertEquals(rowDeletes, Set.copyOf(dml.subList(joinRowDeletes.size(), dml.size())), dml.toString());
        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void deleteFetchedOwnerWithNullifyRuleDeletesJoinRowOnce() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(DeleteRule.NULLIFY);

        // the nullified arc writes nothing for a deleted owner, so the owner's delete is the row's only delete
        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        assertEquals(List.of(DELETE_JOIN_ROW_BY_OWNER, DELETE_OWNER),
                dmlOfCommit(() -> context.deleteObject(owner)));
        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void repointThenDeleteFetchedOwnerWithoutDeleteRule() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(null);

        // the re-point must not turn the join row's delete into an update of it
        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        List<String> dml = dmlOfCommit(() -> {
            owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
            context.deleteObject(owner);
        });
        assertEquals(List.of(DELETE_JOIN_ROW_BY_OWNER, DELETE_OWNER), dml);
        assertEquals(Set.of(new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void repointThenDeleteOwnerLinkedInContextWithoutDeleteRule() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(null);

        FlattenedTest5 owner = context.newObject(FlattenedTest5.class);
        owner.setName("ft5-new");
        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 2));
        context.commitChanges();

        List<String> dml = dmlOfCommit(() -> {
            owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
            context.deleteObject(owner);
        });
        assertEquals(List.of("DELETE FROM COMPLEX_JOIN2 WHERE PK = ?", DELETE_OWNER), dml);
        assertEquals(Set.of(new JoinRow(1, 1), new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void linkThenDeleteFetchedOwnerWithoutJoinRow() throws SQLException {
        createJoinRowDataSet();
        tFlattenedTest5.insert(3, "ft5-3");
        toFT1.setDeleteRule(null);

        // no join row is inserted for an owner this flush deletes, so no insert is ordered against the delete
        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 3);
        List<String> dml = dmlOfCommit(() -> {
            owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
            context.deleteObject(owner);
        });
        assertEquals(List.of(DELETE_JOIN_ROW_BY_OWNER, DELETE_OWNER), dml);
        assertEquals(Set.of(new JoinRow(1, 1), new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void deleteOwnerLinkedInContextWithoutDeleteRule() throws SQLException {
        createJoinRowDataSet();
        toFT1.setDeleteRule(null);

        FlattenedTest5 owner = context.newObject(FlattenedTest5.class);
        owner.setName("ft5-new");
        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
        context.commitChanges();

        // the row's key is tracked from its insert, so it is deleted by that key
        assertEquals(List.of("DELETE FROM COMPLEX_JOIN2 WHERE PK = ?", DELETE_OWNER),
                dmlOfCommit(() -> context.deleteObject(owner)));
        assertEquals(Set.of(new JoinRow(1, 1), new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void relinkAfterClearOfLinkCreatedInContext() throws SQLException {
        createJoinRowDataSet();

        FlattenedTest1 newTarget = context.newObject(FlattenedTest1.class);
        newTarget.setName("ft1-new");
        FlattenedTest5 owner = context.newObject(FlattenedTest5.class);
        owner.setName("ft5-new");
        owner.setToFT1(newTarget);
        context.commitChanges();

        owner.setToFT1(null);
        context.commitChanges();

        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
        context.commitChanges();

        assertEquals(Set.of(new JoinRow(1, 1), new JoinRow(2, 2), new JoinRow(Cayenne.intPKForObject(owner), 3)),
                joinRows());
    }

    @Test
    public void repointTwiceInOneContext() throws SQLException {
        createJoinRowDataSet();

        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
        context.commitChanges();

        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 2));
        context.commitChanges();

        assertEquals(Set.of(new JoinRow(1, 2), new JoinRow(2, 2)), joinRows());
    }

    @Test
    public void repointLinkCreatedInContext() throws SQLException {
        createJoinRowDataSet();

        FlattenedTest1 newTarget = context.newObject(FlattenedTest1.class);
        newTarget.setName("ft1-new");
        FlattenedTest5 owner = context.newObject(FlattenedTest5.class);
        owner.setName("ft5-new");
        owner.setToFT1(newTarget);
        context.commitChanges();

        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 3));
        context.commitChanges();

        assertEquals(Set.of(new JoinRow(1, 1), new JoinRow(2, 2), new JoinRow(Cayenne.intPKForObject(owner), 3)),
                joinRows());
    }

    /** The owner and target a {@code COMPLEX_JOIN2} row refers to, null where the row's foreign key is null. */
    private record JoinRow(Integer ft5, Integer ft1) {
    }

    private Set<JoinRow> joinRows() throws SQLException {
        Set<JoinRow> joinRows = new HashSet<>();
        for(Object[] row : tComplexJoin2.selectAll()) {
            joinRows.add(new JoinRow(intOrNull(row[2]), intOrNull(row[1])));
        }
        return joinRows;
    }

    private static Integer intOrNull(Object value) {
        if(value == null) {
            return null;
        }
        return ((Number) value).intValue();
    }

    /** Runs the change, commits it and returns the SQL of the statements the commit wrote, in order. */
    private List<String> dmlOfCommit(Runnable change) {
        SQL_LOGGER.clear();
        change.run();
        context.commitChanges();
        return List.copyOf(SQL_LOGGER.updates());
    }
}
