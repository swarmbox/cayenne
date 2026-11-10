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

package org.apache.cayenne.access;

import org.apache.cayenne.Cayenne;
import org.apache.cayenne.DataRow;
import org.apache.cayenne.exp.property.RelationshipProperty;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest1;
import org.apache.cayenne.testdo.relationships_flattened.FlattenedTest5;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * FlattenedTest5.toFT1 ("complexJoin2.toFT1") is a to-one through a join table, COMPLEX_JOIN2, entered by a hop that
 * is not to a PK, so a query of FlattenedTest5 does not join COMPLEX_JOIN2 for it and fetches no key of that row. The
 * row built for a FlattenedTest5 must not claim such a key: a null key reads as the join row being absent. This holds
 * for a FlattenedTest5 fetched as the child of a prefetch, FlattenedTest1.ft5Array ("complexJoins2.toFT5"), as for one
 * resolved without a prefetch.
 */
public class PrefetchJoinTableToOneKeyIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_FLATTENED_PROJECT);

    private static final String JOIN_ROW_KEY = "complexJoin2.PK";

    /**
     * How FlattenedTest1.ft5Array is resolved: NONE without a prefetch. A disjoint prefetch over this path fails
     * upstream before it fetches a row, which is a failure of its own, so it is left out.
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
        env.table("FLATTENED_TEST_5", "FT5_ID", "NAME").insert(1, "ft5-1");
        env.table("COMPLEX_JOIN2", "PK", "FT1_FK", "FT5_FK").insert(10, 1, 1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void childRowHasNoJoinRowKey(Fetch fetch) {
        DataContext context = env.context();
        ObjectSelect<FlattenedTest1> query = ObjectSelect.query(FlattenedTest1.class)
                .where(FlattenedTest1.FT1_ID_PK_PROPERTY.eq(1));
        FlattenedTest1 target = fetch.prefetch(query, FlattenedTest1.FT5ARRAY).selectOne(context);

        assertEquals(1, target.getFt5Array().size());
        FlattenedTest5 owner = target.getFt5Array().get(0);
        assertEquals(1, Cayenne.intPKForObject(owner));

        DataRow row = context.getObjectStore().getCachedSnapshot(owner.getObjectId());
        assertNotNull(row, "No cached row");
        assertFalse(row.containsKey(JOIN_ROW_KEY), JOIN_ROW_KEY + " in " + row);
    }
}
