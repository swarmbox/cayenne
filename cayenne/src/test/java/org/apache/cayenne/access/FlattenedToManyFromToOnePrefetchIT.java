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

import org.apache.cayenne.Persistent;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prefetches of a flattened to-many whose first hop is a to-one, so that the hop carrying the to-many's join is not
 * the first one and the parent rows have no column under that hop's prefix:
 * <ul>
 * <li>{@code ToOneHopParent.childrenViaExt} over {@code toExt.children}: a PK-to-PK to-one with the same PK names;</li>
 * <li>{@code ToOneHopParent.childrenViaExt2} over {@code toExt2.children}: a PK-to-PK to-one with different PK
 * names;</li>
 * <li>{@code ToOneHopParent.childrenViaRef} over {@code toRef.children}: an FK to-one that no ObjRelationship maps, so
 * the parent rows lack the FK column;</li>
 * <li>{@code ToOneHopParentRef.childrenViaRef}: the same FK to-one, with the to-one {@code ref} also mapped, so the
 * parent rows carry the FK column.</li>
 * </ul>
 * Joint and disjoint prefetches are the controls for the disjoint-by-id ones.
 */
public class FlattenedToManyFromToOnePrefetchIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_FLATTENED_PROJECT);

    @BeforeEach
    public void createTestData() throws SQLException {
        TableHelper ref = env.table("TO_ONE_HOP_REF", "ID");
        TableHelper parent = env.table("TO_ONE_HOP_PARENT", "ID", "NAME", "REF_ID");
        TableHelper ext = env.table("TO_ONE_HOP_EXT", "ID");
        TableHelper ext2 = env.table("TO_ONE_HOP_EXT2", "PARENT_ID");
        TableHelper child = env.table("TO_ONE_HOP_CHILD", "ID", "EXT_ID", "EXT2_ID", "REF_ID", "NAME");

        ref.insert(10);
        ref.insert(20);
        parent.insert(1, "p1", 10);
        parent.insert(2, "p2", 20);
        parent.insert(3, "p3", null);
        for (int id = 1; id <= 3; id++) {
            ext.insert(id);
            ext2.insert(id);
        }
        child.insert(101, 1, 1, 10, "c101");
        child.insert(102, 1, 1, 10, "c102");
        child.insert(201, 2, 2, 20, "c201");
    }

    @Test
    public void pkToPkSameNameJoint() {
        assertPrefetched("childrenViaExt", PrefetchTreeNode.JOINT_PREFETCH_SEMANTICS);
    }

    @Test
    public void pkToPkSameNameDisjoint() {
        assertPrefetched("childrenViaExt", PrefetchTreeNode.DISJOINT_PREFETCH_SEMANTICS);
    }

    @Test
    public void pkToPkSameNameDisjointById() {
        assertPrefetched("childrenViaExt", PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
    }

    @Test
    public void pkToPkOtherNameJoint() {
        assertPrefetched("childrenViaExt2", PrefetchTreeNode.JOINT_PREFETCH_SEMANTICS);
    }

    @Test
    public void pkToPkOtherNameDisjoint() {
        assertPrefetched("childrenViaExt2", PrefetchTreeNode.DISJOINT_PREFETCH_SEMANTICS);
    }

    @Test
    public void pkToPkOtherNameDisjointById() {
        assertPrefetched("childrenViaExt2", PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
    }

    @Test
    public void fkToOneJoint() {
        assertPrefetched("childrenViaRef", PrefetchTreeNode.JOINT_PREFETCH_SEMANTICS);
    }

    @Test
    public void fkToOneDisjoint() {
        assertPrefetched("childrenViaRef", PrefetchTreeNode.DISJOINT_PREFETCH_SEMANTICS);
    }

    @Test
    @Disabled("Known gap: the parent query fetches no column holding toRef's FK (REF_ID), as no ObjRelationship maps"
            + " toRef, so there is no key to read; the prefetch would need to qualify on the parent PK instead")
    public void fkToOneDisjointById() {
        assertPrefetched("childrenViaRef", PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
    }

    @Test
    public void fkToOneMappedJoint() {
        assertPrefetched("ToOneHopParentRef", "childrenViaRef", PrefetchTreeNode.JOINT_PREFETCH_SEMANTICS);
    }

    @Test
    public void fkToOneMappedDisjoint() {
        assertPrefetched("ToOneHopParentRef", "childrenViaRef", PrefetchTreeNode.DISJOINT_PREFETCH_SEMANTICS);
    }

    @Test
    public void fkToOneMappedDisjointById() {
        assertPrefetched("ToOneHopParentRef", "childrenViaRef", PrefetchTreeNode.DISJOINT_BY_ID_PREFETCH_SEMANTICS);
    }

    private void assertPrefetched(String relationship, int semantics) {
        assertPrefetched("ToOneHopParent", relationship, semantics);
    }

    /**
     * Fetches all parents with the prefetch, then reads each parent's children with queries blocked, so that a
     * relationship the prefetch left unresolved fails instead of being faulted in.
     */
    private void assertPrefetched(String entity, String relationship, int semantics) {
        List<Persistent> parents = ObjectSelect.query(Persistent.class, entity)
                .prefetch(relationship, semantics)
                .select(env.context());

        Map<String, Integer> sizes = new TreeMap<>();
        env.runWithQueriesBlocked(() -> {
            for (Persistent p : parents) {
                List<?> children = (List<?>) p.readProperty(relationship);
                sizes.put((String) p.readProperty("name"), children.size());
            }
        });
        assertEquals(Map.of("p1", 2, "p2", 1, "p3", 0), sizes);
    }
}
