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
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.testdo.inheritance_vertical.IvBase;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.testdo.inheritance_vertical.IvRoot;
import org.apache.cayenne.testdo.inheritance_vertical.IvSub1;
import org.apache.cayenne.testdo.inheritance_vertical.IvSub1Sub1;
import org.apache.cayenne.testdo.inheritance_vertical.IvSub2;
import org.apache.cayenne.testdo.inheritance_vertical.IvSub3;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.unit.RecordingSQLLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A subclass of a vertical-inheritance hierarchy keeps its own columns in a sub-table joined PK to PK to the
 * superclass table. A fetch of the superclass fetches the PKs of these sub-tables, so that the objects it builds know
 * which of their sub-table rows exist: a write to a sub-table column is then an UPDATE of that row and a delete
 * deletes it. The objects a prefetch of any type builds for a relationship that ends at the superclass must know the
 * same, as a plain fetch of the superclass does, and so must the objects other contexts build from the snapshots the
 * prefetch puts in the shared cache.
 * <p>
 * Two shapes: IvOther.base, an FK to IvBase, whose subclass IvImpl keeps its columns in IV_IMPL, and IvSub3.ivRoot,
 * a to-one flattened through the sub-table of IvSub3 ("sub3.ivRoot1"), to IvRoot, whose subclasses IvSub1, IvSub2
 * and IvSub3 keep their columns in IV_SUB1, IV_SUB2 and IV_SUB3, and IvSub1Sub1, a subclass of IvSub1, in IV_SUB1
 * and IV_SUB1_SUB1. The first shape is also reached at the end of a nested prefetch, IvImpl.other1.base.
 * <p>
 * IvSub3.ivRoot and IvImpl.other1 are left out of the disjoint-by-id runs: upstream, that prefetch from a subentity
 * does not resolve the relationship at all, which is a failure of its own.
 */
public class PrefetchSuperclassSubTableKeyIT {

    private static final RecordingSQLLogger SQL_LOGGER = new RecordingSQLLogger();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT)
            .withExtraModules(binder -> binder.bind(SQLLogger.class).toInstance(SQL_LOGGER));

    /**
     * The IvRoot targets of IvSub3 #11 to #14, by name.
     */
    private static final List<String> ROOT_NAMES = List.of("r1", "r2", "r3", "r4");
    private static final List<String> SUB3_NAMES = List.of("s1", "s2", "s3", "s4");

    /**
     * The PKs of the sub-tables of the class of each of the IvRoot targets, which are its own id.
     */
    private static final Map<String, Map<String, Object>> ROOT_KEYS = Map.of(
            "r1", keys("sub1.ID", 1),
            "r2", keys("sub1.ID", 2, "sub1.sub1Sub1.ID", 2),
            "r3", keys("sub2.ID", 3),
            "r4", keys("sub3.ID", 4));

    /**
     * How the objects at the end of the relationship are fetched: PLAIN is the control, a fetch of the superclass
     * itself rather than a prefetch.
     */
    enum Fetch {
        PLAIN(null),
        JOINT(RelationshipProperty::joint),
        DISJOINT(RelationshipProperty::disjoint),
        DISJOINT_BY_ID(RelationshipProperty::disjointById);

        private final Function<RelationshipProperty<?>, PrefetchTreeNode> semantics;

        Fetch(Function<RelationshipProperty<?>, PrefetchTreeNode> semantics) {
            this.semantics = semantics;
        }

        PrefetchTreeNode of(RelationshipProperty<?> relationship) {
            return semantics.apply(relationship);
        }
    }

    /**
     * IvOther #1 and its base, IvImpl #1.
     */
    private record ImplGraph(IvOther other, IvImpl impl) {
    }

    /**
     * IvSub3 #11 to #14 and their IvRoot targets, by name.
     */
    private record RootGraph(List<IvSub3> sources, Map<String, IvRoot> roots) {
    }

    @BeforeEach
    public void createTestData() throws SQLException {
        // the other1 of IvImpl #2 is IvOther #1, whose base is IvImpl #1
        env.table("IV_BASE", "ID", "NAME", "TYPE").insert(1, "impl1", "I").insert(2, "impl2", "I");
        env.table("IV_IMPL", "ID", "ATTR1").insert(1, "attr1");
        env.table("IV_OTHER", "ID", "NAME", "BASE_ID").insert(1, "other1", 1);
        env.table("IV_IMPL", "ID", "ATTR1", "OTHER1_ID").insert(2, "attr2", 1);

        // IvSub3 #11 to #14 point to an IvSub1, an IvSub1Sub1, an IvSub2 and an IvSub3; that IvSub3 points to #9, an
        // IvRoot of no subclass
        env.table("IV_ROOT", "ID", "NAME", "DISCRIMINATOR")
                .insert(1, "r1", "IvSub1")
                .insert(2, "r2", "IvSub1Sub1")
                .insert(3, "r3", "IvSub2")
                .insert(4, "r4", "IvSub3")
                .insert(9, "r9", "IvRoot")
                .insert(11, "s1", "IvSub3")
                .insert(12, "s2", "IvSub3")
                .insert(13, "s3", "IvSub3")
                .insert(14, "s4", "IvSub3");
        env.table("IV_SUB1", "ID", "SUB1_NAME").insert(1, "sub1-1").insert(2, "sub1-2");
        env.table("IV_SUB1_SUB1", "ID", "SUB1_SUB1_NAME").insert(2, "sub1sub1-2");
        env.table("IV_SUB2", "ID", "SUB2_NAME").insert(3, "sub2-3");
        env.table("IV_SUB3").setColumns("ID", "IV_ROOT_ID").setColumnTypes(Types.INTEGER, Types.INTEGER)
                .insert(4, 9)
                .insert(11, 1)
                .insert(12, 2)
                .insert(13, 3)
                .insert(14, 4);

        SQL_LOGGER.clear();
    }

    // IvOther.base -> IvBase, subclass IvImpl

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void implSubTableEditIsAnUpdate(Fetch fetch) throws SQLException {
        DataContext context = env.context();
        fetchImpl(context, fetch).impl().setAttr1("edited");

        commit(context);

        assertUpdatedNotInserted("IV_IMPL");
        assertEquals("edited", values("IV_IMPL", "ATTR1").get(1));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void implDeleteDeletesSubTableRow(Fetch fetch) throws SQLException {
        DataContext context = env.context();
        ImplGraph graph = fetchImpl(context, fetch);

        // IvBase.others denies the delete of a base that has others, so it is resolved to see this one go
        assertEquals(1, graph.impl().getOthers().size());
        graph.other().setBase(null);
        context.deleteObject(graph.impl());

        commit(context);

        assertDeleted("IV_IMPL", "IV_BASE");
        assertEquals(Set.of(2), ids("IV_IMPL"), SQL_LOGGER.updates().toString());
        assertEquals(Set.of(2), ids("IV_BASE"), SQL_LOGGER.updates().toString());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void implSubTableEditViaLocalObjectIsAnUpdate(Fetch fetch) throws SQLException {
        IvImpl impl = fetchImpl(env.context(), fetch).impl();

        DataContext second = newContext();
        second.localObject(impl).setAttr1("edited");

        commit(second);

        assertUpdatedNotInserted("IV_IMPL");
        assertEquals("edited", values("IV_IMPL", "ATTR1").get(1));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void implSubTableEditViaCachedToOneIsAnUpdate(Fetch fetch) throws SQLException {
        fetchImpl(env.context(), fetch);

        // IvOther.base holds the FK, so the second context resolves it from the shared cache
        DataContext second = newContext();
        IvOther other = ObjectSelect.query(IvOther.class).where(IvOther.NAME.eq("other1")).selectOne(second);
        assertInstanceOf(IvImpl.class, other.getBase()).setAttr1("edited");

        commit(second);

        assertUpdatedNotInserted("IV_IMPL");
        assertEquals("edited", values("IV_IMPL", "ATTR1").get(1));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Fetch.class)
    public void implSnapshotCarriesSubTableKey(Fetch fetch) {
        IvImpl impl = fetchImpl(env.context(), fetch).impl();

        assertKeys(snapshot(env.context(), impl), keys("impl.ID", 1));
    }

    // IvImpl.other1.base: the IvImpl of IvOther.base, at the end of a nested prefetch

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void nestedImplSubTableEditIsAnUpdate(Fetch fetch) throws SQLException {
        DataContext context = env.context();
        fetchImplThroughOther1(context, fetch).setAttr1("edited");

        commit(context);

        assertUpdatedNotInserted("IV_IMPL");
        assertEquals("edited", values("IV_IMPL", "ATTR1").get(1));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void nestedImplSnapshotCarriesSubTableKey(Fetch fetch) {
        IvImpl impl = fetchImplThroughOther1(env.context(), fetch);

        assertKeys(snapshot(env.context(), impl), keys("impl.ID", 1));
    }

    // IvSub3.ivRoot ("sub3.ivRoot1") -> IvRoot, subclasses IvSub1 (and its IvSub1Sub1), IvSub2 and IvSub3

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void rootSubTableEditsAreUpdates(Fetch fetch) throws SQLException {
        DataContext context = env.context();
        editRoots(fetchRoots(context, fetch).roots());

        commit(context);

        assertUpdatedNotInserted("IV_SUB1", "IV_SUB1_SUB1", "IV_SUB2");
        assertRootEdits();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void rootDeletesDeleteSubTableRows(Fetch fetch) throws SQLException {
        DataContext context = env.context();
        RootGraph graph = fetchRoots(context, fetch);

        // IV_SUB3.IV_ROOT_ID is mandatory, so the IvSub3s are pointed at an IvRoot that stays
        IvRoot stays = ObjectSelect.query(IvRoot.class).where(IvRoot.NAME.eq("r9")).selectOne(context);
        graph.sources().forEach(source -> source.setIvRoot(stays));
        graph.roots().values().forEach(context::deleteObject);

        commit(context);

        assertDeleted("IV_SUB1", "IV_SUB1_SUB1", "IV_SUB2", "IV_SUB3", "IV_ROOT");
        assertEquals(Set.of(9, 11, 12, 13, 14), ids("IV_ROOT"));
        assertEquals(Set.of(), ids("IV_SUB1"));
        assertEquals(Set.of(), ids("IV_SUB1_SUB1"));
        assertEquals(Set.of(), ids("IV_SUB2"));
        assertEquals(Set.of(11, 12, 13, 14), ids("IV_SUB3"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void rootSubTableEditsViaLocalObjectAreUpdates(Fetch fetch) throws SQLException {
        Map<String, IvRoot> roots = fetchRoots(env.context(), fetch).roots();

        DataContext second = newContext();
        Map<String, IvRoot> local = new HashMap<>();
        roots.forEach((name, root) -> local.put(name, second.localObject(root)));
        editRoots(local);

        commit(second);

        assertUpdatedNotInserted("IV_SUB1", "IV_SUB1_SUB1", "IV_SUB2");
        assertRootEdits();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void rootSubTableEditsViaToOneAreUpdates(Fetch fetch) throws SQLException {
        fetchRoots(env.context(), fetch);

        // IvSub3.ivRoot is flattened, so the second context resolves it with a query of its own
        DataContext second = newContext();
        Map<String, IvRoot> roots = new HashMap<>();
        for (IvSub3 source : ObjectSelect.query(IvSub3.class).where(IvRoot.NAME.in(SUB3_NAMES)).select(second)) {
            IvRoot root = source.getIvRoot();
            roots.put(root.getName(), root);
        }
        editRoots(roots);

        commit(second);

        assertUpdatedNotInserted("IV_SUB1", "IV_SUB1_SUB1", "IV_SUB2");
        assertRootEdits();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fetch.class, names = "DISJOINT_BY_ID", mode = EnumSource.Mode.EXCLUDE)
    public void rootSnapshotsCarrySubTableKeys(Fetch fetch) {
        Map<String, IvRoot> roots = fetchRoots(env.context(), fetch).roots();

        ROOT_KEYS.forEach((name, expected) -> assertKeys(snapshot(env.context(), roots.get(name)), expected));
    }

    private static ImplGraph fetchImpl(DataContext context, Fetch fetch) {
        ObjectSelect<IvOther> otherQuery = ObjectSelect.query(IvOther.class).where(IvOther.NAME.eq("other1"));
        if (fetch == Fetch.PLAIN) {
            IvOther other = otherQuery.selectOne(context);
            IvBase base = ObjectSelect.query(IvBase.class).where(IvBase.NAME.eq("impl1")).selectOne(context);
            return new ImplGraph(other, assertInstanceOf(IvImpl.class, base));
        }

        IvOther other = otherQuery.prefetch(fetch.of(IvOther.BASE)).selectOne(context);
        Object base = other.readPropertyDirectly(IvOther.BASE.getName());
        return new ImplGraph(other, assertInstanceOf(IvImpl.class, base));
    }

    /**
     * Fetches IvImpl #1 as the base of the other1 of IvImpl #2, prefetching both relationships, or for PLAIN with a
     * plain fetch of IvBase.
     */
    private static IvImpl fetchImplThroughOther1(DataContext context, Fetch fetch) {
        if (fetch == Fetch.PLAIN) {
            return fetchImpl(context, fetch).impl();
        }

        IvImpl impl2 = ObjectSelect.query(IvImpl.class)
                .where(IvBase.NAME.eq("impl2"))
                .prefetch(fetch.of(IvImpl.OTHER1))
                .prefetch(fetch.of(IvImpl.OTHER1.dot(IvOther.BASE)))
                .selectOne(context);
        IvOther other = assertInstanceOf(IvOther.class, impl2.readPropertyDirectly(IvImpl.OTHER1.getName()));
        return assertInstanceOf(IvImpl.class, other.readPropertyDirectly(IvOther.BASE.getName()));
    }

    private static RootGraph fetchRoots(DataContext context, Fetch fetch) {
        ObjectSelect<IvSub3> sourceQuery = ObjectSelect.query(IvSub3.class).where(IvRoot.NAME.in(SUB3_NAMES));
        Map<String, IvRoot> roots = new HashMap<>();
        List<IvSub3> sources;
        if (fetch == Fetch.PLAIN) {
            sources = sourceQuery.select(context);
            for (IvRoot root : ObjectSelect.query(IvRoot.class).where(IvRoot.NAME.in(ROOT_NAMES)).select(context)) {
                roots.put(root.getName(), root);
            }
        } else {
            sources = sourceQuery.prefetch(fetch.of(IvSub3.IV_ROOT)).select(context);
            for (IvSub3 source : sources) {
                IvRoot root = assertInstanceOf(IvRoot.class, source.readPropertyDirectly(IvSub3.IV_ROOT.getName()));
                roots.put(root.getName(), root);
            }
        }

        assertEquals(SUB3_NAMES.size(), sources.size());
        assertEquals(Set.copyOf(ROOT_NAMES), roots.keySet());
        assertInstanceOf(IvSub1.class, roots.get("r1"));
        assertInstanceOf(IvSub1Sub1.class, roots.get("r2"));
        assertInstanceOf(IvSub2.class, roots.get("r3"));
        assertInstanceOf(IvSub3.class, roots.get("r4"));
        return new RootGraph(sources, roots);
    }

    /**
     * Edits a column of the sub-table of IvSub1 r1, of the second sub-table of IvSub1Sub1 r2 and of the sub-table of
     * IvSub2 r3.
     */
    private static void editRoots(Map<String, IvRoot> roots) {
        ((IvSub1) roots.get("r1")).setSub1Name("edited-1");
        ((IvSub1Sub1) roots.get("r2")).setSub1Sub1Name("edited-2");
        ((IvSub2) roots.get("r3")).setSub2Name("edited-3");
    }

    private static void assertRootEdits() throws SQLException {
        assertEquals(Map.of(1, "edited-1", 2, "sub1-2"), values("IV_SUB1", "SUB1_NAME"));
        assertEquals(Map.of(2, "edited-2"), values("IV_SUB1_SUB1", "SUB1_SUB1_NAME"));
        assertEquals(Map.of(3, "edited-3"), values("IV_SUB2", "SUB2_NAME"));
    }

    private static DataContext newContext() {
        return (DataContext) env.runtime().newContext();
    }

    private static void commit(DataContext context) {
        SQL_LOGGER.clear();
        context.commitChanges();
    }

    /**
     * Checks that the commit updated each table and inserted into none of them.
     */
    private static void assertUpdatedNotInserted(String... tables) {
        for (String table : tables) {
            assertTrue(SQL_LOGGER.statements("UPDATE", table) > 0,
                    "No UPDATE of " + table + ": " + SQL_LOGGER.updates());
            assertEquals(0, SQL_LOGGER.statements("INSERT INTO", table),
                    "INSERT into " + table + ": " + SQL_LOGGER.updates());
        }
    }

    /**
     * Checks that the commit deleted from each table. The HSQL test schema cascades deletes along its FKs, so the
     * rows being gone afterwards does not show that the commit deleted them.
     */
    private static void assertDeleted(String... tables) {
        for (String table : tables) {
            assertTrue(SQL_LOGGER.statements("DELETE FROM", table) > 0,
                    "No DELETE from " + table + ": " + SQL_LOGGER.updates());
        }
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

    private static Set<Integer> ids(String table) throws SQLException {
        return env.table(table, "ID").selectAll().stream()
                .map(row -> ((Number) row[0]).intValue())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<Integer, Object> values(String table, String column) throws SQLException {
        Map<Integer, Object> values = new HashMap<>();
        for (Object[] row : env.table(table, "ID", column).selectAll()) {
            values.put(((Number) row[0]).intValue(), row[1]);
        }
        return values;
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
