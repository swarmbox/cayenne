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

import org.apache.cayenne.Cayenne;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.exp.property.RelationshipProperty;
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.map.DeleteRule;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest1;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest5;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.unit.RecordingSQLLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context that inserted the join-table row of a to-one flattened through a join table, FlattenedTest5.toFT1
 * ("complexJoin2.toFT1"), tracks that row's key. A fetch of the owner never selects the key, as a query of
 * FlattenedTest5 does not join COMPLEX_JOIN2, so it says nothing about the row and must leave the tracked key alone,
 * also when it fetches the owner as the child of a prefetch, FlattenedTest1.ft5Array. Without a delete rule nothing
 * clears the relationship when the owner is deleted, and only the tracked key gets the join row deleted with it (see
 * RootRowOpProcessor.visitDelete).
 */
public class FlattenedPathRefetchIT {

    private static final RecordingSQLLogger SQL_LOGGER = new RecordingSQLLogger();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_FLATTENED_PROJECT)
            .withExtraModules(binder -> binder.bind(SQLLogger.class).toInstance(SQL_LOGGER));

    private static final CayennePath JOIN_ROW = CayennePath.of("complexJoin2");

    /**
     * How FlattenedTest1.ft5Array is resolved: NONE without a prefetch. A disjoint prefetch over this path fails
     * before it fetches a row, which is a failure of its own, so it is left out.
     */
    enum Fetch {
        NONE(null),
        JOINT(RelationshipProperty::joint),
        DISJOINT_BY_ID(RelationshipProperty::disjointById);

        private final Function<RelationshipProperty<?>, PrefetchTreeNode> semantics;

        Fetch(Function<RelationshipProperty<?>, PrefetchTreeNode> semantics) {
            this.semantics = semantics;
        }

        <T> ObjectSelect<T> prefetch(ObjectSelect<T> query, RelationshipProperty<?> relationship) {
            return semantics == null ? query : query.prefetch(semantics.apply(relationship));
        }
    }

    @BeforeEach
    public void createTestData() throws SQLException {
        env.table("FLATTENED_TEST_1", "FT1_ID", "NAME").insert(1, "ft1-1");
        env.table("FLATTENED_TEST_5", "FT5_ID", "NAME").insert(1, "ft5-1").insert(2, "ft5-2");

        // FlattenedTest5 #2 shares the target, so FlattenedTest5 #1 is fetched with another child
        env.table("COMPLEX_JOIN2", "PK", "FT1_FK", "FT5_FK").insert(20, 1, 2);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void childFetchKeepsJoinRowId(Fetch fetch) {
        DataContext context = newContext();
        FlattenedTest5 owner = insertJoinRow(context);
        ObjectId joinRowId = joinRowId(context, owner);

        fetchAsChild(context, fetch);

        assertEquals(joinRowId, joinRowId(context, owner));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void deleteWithoutDeleteRuleAfterChildFetchDeletesJoinRow(Fetch fetch) throws SQLException {
        ObjRelationship toFT1 = env.entityResolver().getObjEntity("FlattenedTest5")
                .getRelationship(FlattenedTest5.TO_FT1.getName());
        DeleteRule rule = toFT1.getDeleteRule();
        toFT1.setDeleteRule(null);
        try {
            DataContext context = newContext();
            FlattenedTest5 owner = insertJoinRow(context);
            fetchAsChild(context, fetch);

            context.deleteObject(owner);
            SQL_LOGGER.clear();
            context.commitChanges();

            // the HSQL test schema cascades deletes along its FKs, so only the SQL shows whether the commit deleted
            // the join row
            assertEquals(1, SQL_LOGGER.statements("DELETE FROM", "COMPLEX_JOIN2"), SQL_LOGGER.updates().toString());
            assertEquals(1, SQL_LOGGER.statements("DELETE FROM", "FLATTENED_TEST_5"), SQL_LOGGER.updates().toString());
            assertEquals(1, env.table("COMPLEX_JOIN2", "PK").getRowCount());
        } finally {
            toFT1.setDeleteRule(rule);
        }
    }

    /**
     * Links FlattenedTest5 #1 to FlattenedTest1 #1, which inserts a join row the context then tracks.
     */
    private static FlattenedTest5 insertJoinRow(DataContext context) {
        FlattenedTest5 owner = Cayenne.objectForPK(context, FlattenedTest5.class, 1);
        owner.setToFT1(Cayenne.objectForPK(context, FlattenedTest1.class, 1));
        context.commitChanges();
        assertNotNull(joinRowId(context, owner), "The context does not track the join row it inserted");
        return owner;
    }

    /**
     * Fetches FlattenedTest1 #1 and its ft5Array, of which FlattenedTest5 #1 is one.
     */
    private static void fetchAsChild(DataContext context, Fetch fetch) {
        ObjectSelect<FlattenedTest1> query = ObjectSelect.query(FlattenedTest1.class)
                .where(FlattenedTest1.FT1_ID_PK_PROPERTY.eq(1));
        FlattenedTest1 target = fetch.prefetch(query, FlattenedTest1.FT5ARRAY).selectOne(context);
        assertTrue(target.getFt5Array().stream().anyMatch(child -> Cayenne.intPKForObject(child) == 1),
                "FlattenedTest5 #1 not fetched");
    }

    private static ObjectId joinRowId(DataContext context, FlattenedTest5 owner) {
        return context.getObjectStore().getFlattenedId(owner.getObjectId(), JOIN_ROW);
    }

    private static DataContext newContext() {
        return (DataContext) env.runtime().newContext();
    }
}
