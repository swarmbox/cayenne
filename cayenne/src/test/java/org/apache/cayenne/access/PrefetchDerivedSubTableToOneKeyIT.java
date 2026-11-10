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
import org.apache.cayenne.testdo.inheritance_vertical.IvConcrete;
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
 * IvAbstract.relatedConcrete ("relatedConcrete.abstract") is a to-one whose FK is on IV_CONCRETE, the sub-table of its
 * target IvConcrete, entered by a hop that is not to a PK, so a query of the source does not join that row and fetches
 * no key of it. The row built for the source must not claim such a key: a null key reads as the row being absent.
 * This holds for a source fetched as the child of a prefetch, IvConcrete.children, as for one resolved without a
 * prefetch.
 */
public class PrefetchDerivedSubTableToOneKeyIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    private static final String TARGET_ROW_KEY = "relatedConcrete.ID";

    /**
     * How IvConcrete.children is resolved: NONE without a prefetch. A disjoint prefetch over this path fails
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
        // #1 is the child of #10 and the source of #2's relatedAbstract
        env.table("IV_ABSTRACT", "ID", "PARENT_ID", "TYPE")
                .insert(10, null, "S")
                .insert(1, 10, "S")
                .insert(2, null, "S");
        env.table("IV_CONCRETE", "ID", "NAME", "RELATED_ABSTRACT_ID")
                .insert(10, "p", null)
                .insert(1, "a", null)
                .insert(2, "b", 1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void childRowHasNoTargetRowKey(Fetch fetch) {
        DataContext context = env.context();
        ObjectSelect<IvConcrete> query = ObjectSelect.query(IvConcrete.class).where(IvConcrete.ID_PK_PROPERTY.eq(10));
        IvConcrete parent = fetch.prefetch(query, IvConcrete.CHILDREN).selectOne(context);

        assertEquals(1, parent.getChildren().size());
        IvConcrete source = parent.getChildren().get(0);
        assertEquals(1, Cayenne.intPKForObject(source));

        DataRow row = context.getObjectStore().getCachedSnapshot(source.getObjectId());
        assertNotNull(row, "No cached row");
        assertFalse(row.containsKey(TARGET_ROW_KEY), TARGET_ROW_KEY + " in " + row);
    }
}
