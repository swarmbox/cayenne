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

import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical.IvSub1Sub1;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flush that inserts flattened intermediate rows must leave nothing behind in
 * {@code DataContextObjectStore.trackedFlattenedPaths} that no reader can name and no cleanup path can reclaim.
 * <p>
 * The owner-keyed entries the flattened-attribute walk stages are the useful ones. A further entry per
 * intermediate row, keyed on the row operation's own change id - a synthetic {@code db:}-prefixed temporary -
 * would not be: that key is never registered in {@code objectMap}, so every reader passes a real object id and
 * misses it, and every removal path keys on an id that leaves {@code objectMap} and so cannot reach it. Such
 * entries would accumulate for the life of the {@code DataContextObjectStore} and serialize with it.
 * <p>
 * This test lives in {@code org.apache.cayenne.access} rather than beside the flush classes so that the
 * {@code protected} {@code trackedFlattenedPaths} field is readable without reflection.
 * <p>
 * The fixture is load-bearing: the owner is a {@code NEW} object registered under a permanent id that sets
 * <em>both</em> of its flattened attributes, so its own attribute walk mints temporary ids and the intermediate
 * rows are genuinely inserted. A target that sets no attributes instead reaches the derived branch, whose ids
 * are already permanent, and leaks nothing.
 */
public class SyntheticFlattenedKeyLeakIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    private static final ObjectId OWNER = ObjectId.of("IvSub1Sub1", "ID", 1);

    private static final CayennePath SUB1 = CayennePath.of("sub1");

    private static final CayennePath SUB1_SUB1 = CayennePath.of("sub1.sub1Sub1");

    TableHelper ivRootTable;
    TableHelper ivSub1Table;
    TableHelper ivSub1Sub1Table;

    @BeforeEach
    public void setUp() {
        ivRootTable = env.table("IV_ROOT").setColumns("ID", "DISCRIMINATOR", "NAME")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.VARCHAR);
        ivSub1Table = env.table("IV_SUB1").setColumns("ID", "SUB1_NAME", "SUB1_PRICE")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.DOUBLE, Types.INTEGER);
        ivSub1Sub1Table = env.table("IV_SUB1_SUB1").setColumns("ID", "SUB1_SUB1_NAME", "SUB1_SUB1_PRICE")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);
    }

    @AfterEach
    public void cleanUp() throws SQLException {
        ivSub1Sub1Table.deleteAll();
        ivSub1Table.deleteAll();
        ivRootTable.deleteAll();
    }

    /**
     * The demonstrating case. After one commit through the flattened-attribute walk the only owner tracked must
     * be the object itself, with no synthetic {@code db:}-keyed entry per inserted intermediate row alongside
     * it.
     */
    @Test
    public void theObjectIsTheOnlyTrackedOwnerAfterCommit() throws SQLException {
        DataContextObjectStore store = commitOwnerSettingBothFlattenedAttributes();

        assertEquals(Collections.singleton(OWNER), trackedOwners(store),
                "an inserted intermediate row must not leave an entry keyed on its own synthetic id");
    }

    /**
     * A guard rather than defect coverage. It pins the reason the promoted id is
     * left alone: the tracked value's {@code getIdSnapshot()} resolves through the replacement map the flush
     * attached, so readers already see the real primary key and no post-flush rewrite is needed. A future
     * "fix" that resolves the id at promotion has to keep this true.
     */
    @Test
    public void trackedValueResolvesToThePermanentRowKeyAfterCommit() throws SQLException {
        DataContextObjectStore store = commitOwnerSettingBothFlattenedAttributes();

        assertEquals(OWNER.getIdSnapshot(), trackedId(store, SUB1).getIdSnapshot(),
                "the IV_SUB1 row's tracked id must resolve to its real primary key");
        assertEquals(OWNER.getIdSnapshot(), trackedId(store, SUB1_SUB1).getIdSnapshot(),
                "the IV_SUB1_SUB1 row's tracked id must resolve to its real primary key");
    }

    /**
     * A second guard rather than defect coverage. Promotion hands each staged id
     * to the {@code DataContextObjectStore} byte-identically, so a tracked value staged as a temporary is still that same
     * temporary after commit. Resolving it at promotion instead would be invisible to every other assertion
     * here, since the resolved id's snapshot is the same map; this is what makes that change observable.
     */
    @Test
    public void trackedValueIsStillTheStagedTemporaryIdAfterCommit() throws SQLException {
        DataContextObjectStore store = commitOwnerSettingBothFlattenedAttributes();

        ObjectId sub1Id = trackedId(store, SUB1);
        assertTrue(sub1Id.isTemporary(), "the IV_SUB1 row's tracked id must still be the staged temporary");
        assertTrue(sub1Id.isReplacementIdAttached(), "the IV_SUB1 row's temporary must carry its replacement");

        ObjectId sub1Sub1Id = trackedId(store, SUB1_SUB1);
        assertTrue(sub1Sub1Id.isTemporary(),
                "the IV_SUB1_SUB1 row's tracked id must still be the staged temporary");
        assertTrue(sub1Sub1Id.isReplacementIdAttached(),
                "the IV_SUB1_SUB1 row's temporary must carry its replacement");
    }

    private DataContextObjectStore commitOwnerSettingBothFlattenedAttributes() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvSub1Sub1");
        IvSub1Sub1 owner = (IvSub1Sub1) descriptor.createObject();
        owner.setObjectId(OWNER);
        context.registerNewObject(owner);
        owner.setSub1Name("sub1");
        owner.setSub1Sub1Name("sub1-sub1");

        context.commitChanges();

        assertEquals(1, ivSub1Table.getRowCount(), "precondition: the first intermediate row was inserted");
        assertEquals(1, ivSub1Sub1Table.getRowCount(), "precondition: the second intermediate row was inserted");

        return ((DataContext) context).getObjectStore();
    }

    private static Set<ObjectId> trackedOwners(DataContextObjectStore store) {
        assertNotNull(store.trackedFlattenedPaths, "precondition: the commit tracked at least one flattened path");
        return store.trackedFlattenedPaths.keySet();
    }

    private static ObjectId trackedId(DataContextObjectStore store, CayennePath path) {
        ObjectId id = store.getFlattenedId(OWNER, path);
        assertNotNull(id, "precondition: the owner's route '" + path + "' is tracked");
        return id;
    }
}
