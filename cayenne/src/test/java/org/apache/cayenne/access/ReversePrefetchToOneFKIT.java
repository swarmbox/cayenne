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

import org.apache.cayenne.Fault;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.testdo.relationships_to_one_fk.ToOneFK1;
import org.apache.cayenne.testdo.relationships_to_one_fk.ToOneFK2;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that prefetching ToOneFK1.toPK also populates its reverse to-one ToOneFK2.toOneToFK.
 * <p>
 * The queries are not qualified: the map has no plain attributes, so a qualifier would be a db:id path, and the rows
 * each test seeds are the rows it selects.
 */
public class ReversePrefetchToOneFKIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_TO_ONE_FK_PROJECT);

    private void createTwoSourcesWithTargets() throws Exception {
        env.table("TO_ONE_FK2", "TO_ONE_FK2_PK")
                .insert(1)
                .insert(2);
        env.table("TO_ONE_FK1", "TO_ONE_FK1_PK", "TO_ONE_FK1_FK")
                .insert(1, 1)
                .insert(2, 2);
    }

    private void runAndAssertReversePopulated(PrefetchTreeNode prefetch) throws Exception {
        createTwoSourcesWithTargets();

        List<ToOneFK1> sources = ObjectSelect.query(ToOneFK1.class)
                .prefetch(prefetch)
                .select(env.context());

        assertEquals(2, sources.size());
        env.runWithQueriesBlocked(() -> {
            for (ToOneFK1 source : sources) {
                ToOneFK2 target = (ToOneFK2) source.readPropertyDirectly(ToOneFK1.TO_PK.getName());
                assertNotNull(target);

                Object reverse = target.readPropertyDirectly(ToOneFK2.TO_ONE_TO_FK.getName());
                assertFalse(reverse instanceof Fault, "reverse to-one should not be a Fault after prefetch");
                assertSame(source, reverse, "reverse should point back to the owning parent");
            }
        });
    }

    @Test
    public void reverseToOnePopulatedJoint() throws Exception {
        runAndAssertReversePopulated(ToOneFK1.TO_PK.joint());
    }

    @Test
    public void reverseToOnePopulatedDisjoint() throws Exception {
        runAndAssertReversePopulated(ToOneFK1.TO_PK.disjoint());
    }

    @Test
    public void reverseToOnePopulatedDisjointById() throws Exception {
        runAndAssertReversePopulated(ToOneFK1.TO_PK.disjointById());
    }

    /**
     * If the target's reverse, or the source's to-one, has pending user changes, the prefetch must not overwrite them
     * with the association in the source row.
     */
    @Test
    public void modifiedReverseNotOverwritten() throws Exception {
        // one committed source of one target
        env.table("TO_ONE_FK2", "TO_ONE_FK2_PK").insert(1);
        env.table("TO_ONE_FK1", "TO_ONE_FK1_PK", "TO_ONE_FK1_FK").insert(1, 1);

        DataContext context = env.context();
        ToOneFK2 target = ObjectSelect.query(ToOneFK2.class).selectOne(context);
        ToOneFK1 newSource = context.newObject(ToOneFK1.class);
        // the 1:1 is exclusive, so this also detaches the committed source, the previous source of the target, in
        // memory
        target.setToOneToFK(newSource);
        assertEquals(PersistenceState.MODIFIED, target.getPersistenceState());

        // the committed source's row still has the FK of the target, so the disjoint prefetch pulls the target back
        // in and links it
        ToOneFK1 committedSource = ObjectSelect.query(ToOneFK1.class)
                .prefetch(ToOneFK1.TO_PK.disjoint())
                .selectOne(context);

        assertNotNull(committedSource);
        assertNull(committedSource.getToPK(), "pending user modification of toPK must survive the prefetch");
        assertSame(newSource, target.getToOneToFK(), "pending user modification of toOneToFK must survive the prefetch");
    }
}
