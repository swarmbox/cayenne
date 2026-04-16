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
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.testdo.inheritance_vertical.IvAbstract;
import org.apache.cayenne.testdo.inheritance_vertical.IvConcrete;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that prefetching IvConcrete.relatedAbstract also populates its reverse to-one IvAbstract.relatedConcrete.
 */
public class ReversePrefetchVerticalInheritanceIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    @BeforeEach
    public void createTwoSourcesWithTargets() throws Exception {
        // #1 and #2 are the sources, related to #3 and #4. The targets are left out of the root query, so the
        // prefetch is what resolves them.
        env.table("IV_ABSTRACT", "ID", "PARENT_ID", "TYPE")
                .insert(1, null, "S")
                .insert(2, null, "S")
                .insert(3, null, "S")
                .insert(4, null, "S");
        env.table("IV_CONCRETE", "ID", "NAME", "RELATED_ABSTRACT_ID")
                .insert(1, "a", 3)
                .insert(2, "b", 4)
                .insert(3, "c", null)
                .insert(4, "d", null);
    }

    private void runAndAssertReversePopulated(PrefetchTreeNode prefetch) {
        List<IvConcrete> sources = ObjectSelect.query(IvConcrete.class)
                // a plain attribute rather than a db:id path, which a disjoint prefetch can rebase onto its query
                .where(IvConcrete.NAME.in("a", "b"))
                .prefetch(prefetch)
                .select(env.context());

        assertEquals(2, sources.size());
        env.runWithQueriesBlocked(() -> {
            for (IvConcrete source : sources) {
                IvAbstract target = (IvAbstract) source.readPropertyDirectly(IvConcrete.RELATED_ABSTRACT.getName());
                assertNotNull(target);

                Object reverse = target.readPropertyDirectly(IvAbstract.RELATED_CONCRETE.getName());
                assertFalse(reverse instanceof Fault, "reverse to-one should not be a Fault after prefetch");
                assertSame(source, reverse, "reverse should point back to the owning parent");
            }
        });
    }

    @Test
    public void reverseToOnePopulatedJoint() {
        runAndAssertReversePopulated(IvConcrete.RELATED_ABSTRACT.joint());
    }

    @Test
    public void reverseToOnePopulatedDisjoint() {
        runAndAssertReversePopulated(IvConcrete.RELATED_ABSTRACT.disjoint());
    }

    @Test
    public void reverseToOnePopulatedDisjointById() {
        runAndAssertReversePopulated(IvConcrete.RELATED_ABSTRACT.disjointById());
    }
}
