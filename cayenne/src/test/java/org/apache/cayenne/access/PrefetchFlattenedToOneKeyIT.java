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

import org.apache.cayenne.DataRow;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.exp.property.RelationshipProperty;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flattened to-one whose first hop goes to a PK has its key fetched through the path joins, and the snapshot
 * carries it under a path-qualified name, e.g. "impl.OTHER1_ID" for IvImpl.other1 ("impl.other1", FK on the
 * vertical-inheritance sub-table IV_IMPL). The objects a prefetch of any type builds must get these keys with their
 * values, as a plain fetch gives them, whatever the shape of the incoming path.
 * <p>
 * IvImpl.other1 is left out of the disjoint-by-id runs: upstream, that prefetch from a subentity does not resolve the
 * relationship at all, which is a failure of its own.
 */
public class PrefetchFlattenedToOneKeyIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    /**
     * The keys of the IvImpl flattened to-ones other1, other2 and other3, by impl id.
     */
    private static final Map<Integer, Map<String, Object>> IMPL_KEYS = Map.of(
            1, keys("impl.OTHER1_ID", 1, "impl.OTHER2_ID", 1, "impl.OTHER3_ID", 1),
            2, keys("impl.OTHER1_ID", 1, "impl.OTHER2_ID", null, "impl.OTHER3_ID", 1));

    /**
     * The key of IvOther #2's flattened to-one impl ("impl.base"), whose first hop is an FK on IV_OTHER, and the value
     * of that FK.
     */
    private static final Map<String, Object> OTHER2_KEYS = keys("impl.ID", 1);
    private static final Map<String, Object> OTHER2_FIRST_HOP_FKS = keys("IMPL_ID", 1);

    enum Prefetch {
        JOINT(RelationshipProperty::joint),
        DISJOINT(RelationshipProperty::disjoint),
        DISJOINT_BY_ID(RelationshipProperty::disjointById);

        private final Function<RelationshipProperty<?>, PrefetchTreeNode> semantics;

        Prefetch(Function<RelationshipProperty<?>, PrefetchTreeNode> semantics) {
            this.semantics = semantics;
        }

        PrefetchTreeNode of(RelationshipProperty<?> relationship) {
            return semantics.apply(relationship);
        }
    }

    @BeforeEach
    public void createTestData() throws SQLException {
        // impls #1 and #2 point to IvOther #1 through "impl.other1" and "impl.other3"; IvOther #2 points to impl #1
        // through "impl.base", and impl #3 points to IvOther #2 through "impl.other1"
        env.table("IV_BASE", "ID", "NAME", "TYPE")
                .insert(1, "impl1", "I")
                .insert(2, "impl2", "I")
                .insert(3, "impl3", "I");
        env.table("IV_OTHER").setColumns("ID", "NAME", "IMPL_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER)
                .insert(1, "other1", null);
        env.table("IV_IMPL").setColumns("ID", "OTHER1_ID", "OTHER2_ID", "OTHER3_ID")
                .setColumnTypes(Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER)
                .insert(1, 1, 1, 1)
                .insert(2, 1, null, 1);
        env.table("IV_OTHER").setColumns("ID", "NAME", "IMPL_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER)
                .insert(2, "other2", 1);
        env.table("IV_IMPL").setColumns("ID", "OTHER1_ID", "OTHER2_ID", "OTHER3_ID")
                .setColumnTypes(Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER)
                .insert(3, 2, null, null);
    }

    /**
     * Deletes the IV_IMPL and IV_OTHER rows, which reference each other: IvOther #2 points to impl #1, and the impls
     * point to IvOther #1 and #2. The env's clean-up deletes one table at a time, so on a database whose test FKs
     * don't cascade, it would fail on these rows before every later test of this mapping.
     */
    @AfterEach
    public void deleteImplOtherCycle() throws SQLException {
        env.table("IV_OTHER", "IMPL_ID").update().set("IMPL_ID", null, Types.INTEGER).execute();
        env.table("IV_IMPL").deleteAll();
        env.table("IV_OTHER").deleteAll();
    }

    // controls: a plain fetch

    @Test
    public void plainFetchOfImpls() {
        List<IvImpl> impls = ObjectSelect.query(IvImpl.class)
                .where(IvImpl.NAME.in("impl1", "impl2"))
                .select(env.context());

        assertEquals(2, impls.size());
        assertImplKeys(env.context(), impls);
    }

    @Test
    public void plainFetchOfOther() {
        IvOther other = ObjectSelect.query(IvOther.class)
                .where(IvOther.NAME.eq("other2"))
                .selectOne(env.context());

        assertOther2Keys(snapshot(env.context(), other));
    }

    // (a) the keys of IvImpl's flattened to-ones, after a PK-to-PK first hop, reached through a to-many

    @ParameterizedTest(name = "{0}")
    @EnumSource(Prefetch.class)
    public void keysAfterPkFirstHopViaToMany(Prefetch prefetch) {
        IvOther other = ObjectSelect.query(IvOther.class)
                .where(IvOther.NAME.eq("other1"))
                .prefetch(prefetch.of(IvOther.IMPLS_WITH_INVERSE))
                .selectOne(env.context());

        @SuppressWarnings("unchecked")
        List<IvImpl> impls = (List<IvImpl>) other.readPropertyDirectly(IvOther.IMPLS_WITH_INVERSE.getName());
        assertEquals(2, impls.size());
        assertImplKeys(env.context(), impls);
    }

    // (b) the same, reached through a to-one

    @ParameterizedTest(name = "{0}")
    @EnumSource(Prefetch.class)
    public void keysAfterPkFirstHopViaToOne(Prefetch prefetch) {
        IvOther other = ObjectSelect.query(IvOther.class)
                .where(IvOther.NAME.eq("other2"))
                .prefetch(prefetch.of(IvOther.IMPL))
                .selectOne(env.context());

        IvImpl impl = (IvImpl) other.readPropertyDirectly(IvOther.IMPL.getName());
        assertNotNull(impl);
        assertImplKeys(env.context(), List.of(impl));
    }

    // (c) the key of IvOther's flattened to-one impl, after an FK-to-PK first hop

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Prefetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void keysAfterFkFirstHop(Prefetch prefetch) {
        IvOther other = prefetchOther2(env.context(), prefetch);

        assertOther2Keys(snapshot(env.context(), other));
    }

    // (d) the snapshot cache is shared: a prefetch in one context replaces the snapshots another context fetched

    @ParameterizedTest(name = "{0}")
    @EnumSource(Prefetch.class)
    public void sharedSnapshotKeepsKeysAfterPkFirstHop(Prefetch prefetch) {
        DataContext first = env.context();
        List<IvImpl> impls = ObjectSelect.query(IvImpl.class)
                .where(IvImpl.NAME.in("impl1", "impl2"))
                .select(first);
        assertEquals(2, impls.size());

        DataContext second = (DataContext) env.runtime().newContext();
        ObjectSelect.query(IvOther.class)
                .where(IvOther.NAME.eq("other1"))
                .prefetch(prefetch.of(IvOther.IMPLS_WITH_INVERSE))
                .select(second);

        assertImplKeys(first, impls);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Prefetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void sharedSnapshotKeepsKeysAfterFkFirstHop(Prefetch prefetch) {
        DataContext first = env.context();
        IvOther other = ObjectSelect.query(IvOther.class)
                .where(IvOther.NAME.eq("other2"))
                .selectOne(first);

        prefetchOther2((DataContext) env.runtime().newContext(), prefetch);

        assertOther2Keys(snapshot(first, other));
    }

    /**
     * Fetches impl #3 with a prefetch of its other1, IvOther #2, and returns that IvOther.
     */
    private static IvOther prefetchOther2(DataContext context, Prefetch prefetch) {
        IvImpl impl = ObjectSelect.query(IvImpl.class)
                .where(IvImpl.NAME.eq("impl3"))
                .prefetch(prefetch.of(IvImpl.OTHER1))
                .selectOne(context);

        IvOther other = (IvOther) impl.readPropertyDirectly(IvImpl.OTHER1.getName());
        assertNotNull(other);
        return other;
    }

    private static void assertImplKeys(DataContext context, List<IvImpl> impls) {
        for (IvImpl impl : impls) {
            int id = (Integer) impl.getObjectId().getIdSnapshot().get("ID");
            assertKeys(snapshot(context, impl), IMPL_KEYS.get(id));
        }
    }

    private static void assertOther2Keys(DataRow snapshot) {
        assertKeys(snapshot, OTHER2_KEYS);

        // the FKs of the first hops are not fetched for a flattened path, so the snapshot must not claim a value
        OTHER2_FIRST_HOP_FKS.forEach((key, value) -> assertTrue(
                !snapshot.containsKey(key) || Objects.equals(value, snapshot.get(key)),
                key + " in snapshot: " + snapshot));
    }

    /**
     * Checks that each key is in the snapshot, with its value, null included.
     */
    private static void assertKeys(DataRow snapshot, Map<String, Object> expected) {
        expected.forEach((key, value) -> {
            assertTrue(snapshot.containsKey(key), key + " not in snapshot: " + snapshot);
            assertEquals(value, snapshot.get(key), key + " in snapshot: " + snapshot);
        });
    }

    private static DataRow snapshot(DataContext context, Persistent object) {
        DataRow snapshot = context.getObjectStore().getSnapshot(object.getObjectId());
        assertNotNull(snapshot, "No snapshot for " + object.getObjectId());
        return snapshot;
    }

    /**
     * Returns a map of the key/value pairs, which may hold nulls, unlike Map.of().
     */
    private static Map<String, Object> keys(Object... pairs) {
        Map<String, Object> keys = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            keys.put((String) pairs[i], pairs[i + 1]);
        }
        return keys;
    }
}
