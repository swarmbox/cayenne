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
import java.util.Set;

import org.apache.cayenne.Cayenne;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest1;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest5;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
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

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_FLATTENED_PROJECT);

    private DataContext context;

    private TableHelper tFlattenedTest1;
    private TableHelper tFlattenedTest5;
    private TableHelper tComplexJoin2;

    @BeforeEach
    public void setUp() {
        context = env.context();

        tFlattenedTest1 = env.table("FLATTENED_TEST_1", "FT1_ID", "NAME");
        tFlattenedTest5 = env.table("FLATTENED_TEST_5", "FT5_ID", "NAME");
        tComplexJoin2 = env.table("COMPLEX_JOIN2", "PK", "FT1_FK", "FT5_FK");
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
}
