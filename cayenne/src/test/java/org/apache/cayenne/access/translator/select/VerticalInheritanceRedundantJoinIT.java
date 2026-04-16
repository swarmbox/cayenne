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

package org.apache.cayenne.access.translator.select;

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.access.DataNode;
import org.apache.cayenne.dba.DbAdapter;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.query.ColumnSelect;
import org.apache.cayenne.query.FluentSelect;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.testdo.inheritance_vertical.IvSub1Sub1;
import org.apache.cayenne.unit.di.runtime.CayenneProjects;
import org.apache.cayenne.unit.di.runtime.RuntimeCase;
import org.apache.cayenne.unit.di.runtime.UseCayenneRuntime;
import org.apache.cayenne.util.Util;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that the SQL translator does not generate redundant round-trip joins
 * when resolving attributes on vertically inherited entities reached through
 * a relationship.
 *
 * @since 5.0
 */
@UseCayenneRuntime(CayenneProjects.INHERITANCE_VERTICAL_PROJECT)
public class VerticalInheritanceRedundantJoinIT extends RuntimeCase {

    @Inject
    private DataNode dataNode;

    @Inject
    private ObjectContext context;

    /**
     * IvOther.IMPL has db-path "impl.base" (IV_OTHER -> IV_IMPL -> IV_BASE).
     * Resolving an IV_IMPL attribute on the far side of that path must reuse
     * the alias from the first hop rather than joining IV_IMPL again.
     */
    @Test
    public void testNoRedundantChildTableJoinInQualifier() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .where(IvOther.IMPL.dot(IvImpl.ATTR1).eq("test"));

        String sql = translateToSql(q);

        int joinCount = countJoins(sql, "IV_IMPL");
        assertEquals("IV_IMPL should be joined exactly once, but got SQL: " + sql,
                1, joinCount);
    }

    /**
     * Two child attributes through the same relationship must still produce a
     * single IV_IMPL join.
     */
    @Test
    public void testNoRedundantChildTableJoinWithMultipleChildAttrs() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .where(IvOther.IMPL.dot(IvImpl.ATTR1).eq("test"))
                .and(IvOther.IMPL.dot(IvImpl.ATTR2).eq("test2"));

        String sql = translateToSql(q);

        int joinCount = countJoins(sql, "IV_IMPL");
        assertEquals("IV_IMPL should be joined exactly once, but got SQL: " + sql,
                1, joinCount);
    }

    /**
     * Mixing child and parent table attributes through the same relationship
     * should produce exactly one join to each table.
     */
    @Test
    public void testNoRedundantJoinWithMixedParentAndChildAttrs() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .where(IvOther.IMPL.dot(IvImpl.ATTR1).eq("test"))
                .and(IvOther.IMPL.dot(IvImpl.NAME).eq("name"));

        String sql = translateToSql(q);

        int implJoinCount = countJoins(sql, "IV_IMPL");
        int baseJoinCount = countJoins(sql, "IV_BASE");
        assertEquals("IV_IMPL should be joined exactly once, but got SQL: " + sql,
                1, implJoinCount);
        assertEquals("IV_BASE should be joined exactly once, but got SQL: " + sql,
                1, baseJoinCount);
    }

    /**
     * The ORDER BY path is separate from the WHERE path through the
     * translator, verify the redirect applies there too.
     */
    @Test
    public void testNoRedundantChildTableJoinInOrderBy() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .orderBy(IvOther.IMPL.dot(IvImpl.ATTR1).asc());

        String sql = translateToSql(q);

        int joinCount = countJoins(sql, "IV_IMPL");
        assertEquals("IV_IMPL should be joined exactly once, but got SQL: " + sql,
                1, joinCount);
    }

    /**
     * When the redirect leaves the parent table unreferenced, the shared PK
     * join must be pruned out of the query entirely.
     */
    @Test
    public void testUnusedParentTableJoinPruned() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .where(IvOther.IMPL.dot(IvImpl.ATTR1).eq("test"));

        String sql = translateToSql(q);

        int baseJoinCount = countJoins(sql, "IV_BASE");
        assertEquals("IV_BASE should not be joined when only child attributes are used, but got SQL: " + sql,
                0, baseJoinCount);
    }

    /**
     * When a parent-table attribute is referenced, the parent join must be
     * preserved (negative case for {@link #testUnusedParentTableJoinPruned()}).
     */
    @Test
    public void testUsedParentTableJoinPreserved() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .where(IvOther.IMPL.dot(IvImpl.NAME).eq("test"));

        String sql = translateToSql(q);

        int baseJoinCount = countJoins(sql, "IV_BASE");
        assertEquals("IV_BASE should be joined when parent attribute is used, but got SQL: " + sql,
                1, baseJoinCount);
    }

    /**
     * The prefetch's IV_BASE join carries an inheritance discriminator
     * qualifier and must survive pruning, even when the WHERE clause's
     * IV_BASE join is correctly pruned.
     */
    @Test
    public void testPrefetchJoinsNotOverPruned() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .prefetch(IvOther.IMPL.joint())
                .where(IvOther.IMPL.dot(IvImpl.ATTR1).eq("test"));

        String sql = translateToSql(q);

        int implJoinCount = countJoins(sql, "IV_IMPL");
        int baseJoinCount = countJoins(sql, "IV_BASE");
        assertEquals("IV_IMPL should be joined exactly twice (WHERE + prefetch), but got SQL: " + sql,
                2, implJoinCount);
        assertEquals("IV_BASE should be joined exactly once (prefetch only, WHERE's is pruned), but got SQL: " + sql,
                1, baseJoinCount);
    }

    /**
     * The subquery has its own {@link TableTree}, this verifies that its
     * pruning/redirect logic does not interfere with the outer query's.
     */
    @Test
    public void testExistsSubqueryWithVerticalInheritance() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .where(IvOther.IMPL.exists())
                .and(IvOther.IMPL.dot(IvImpl.ATTR1).eq("test"));

        String sql = translateToSql(q);

        assertTrue("SQL should contain an EXISTS subquery, but got: " + sql,
                sql.toUpperCase().contains("EXISTS"));
        // The subquery's IV_IMPL is its root (FROM, not JOIN). The outer's
        // IV_IMPL join comes from the ATTR1 access.
        assertEquals("IV_IMPL should be joined exactly once in the outer query, but got SQL: " + sql,
                1, countJoins(sql, "IV_IMPL"));
        assertEquals("IV_BASE should not be joined (unused by both outer and subquery), but got SQL: " + sql,
                0, countJoins(sql, "IV_BASE"));
    }

    /**
     * IvOther.SUB1_SUB1 has db-path "sub1Sub1.master.master" (FK + 2 inheritance
     * hops). Accessing an IV_SUB1_SUB1 attribute on the far side must redirect
     * back through the chain rather than rejoining.
     */
    @Test
    public void testThreeLevelInheritanceRedirect() {
        ObjectSelect<IvOther> q = ObjectSelect.query(IvOther.class)
                .where(IvOther.SUB1_SUB1.dot(IvSub1Sub1.SUB1SUB1NAME).eq("test"));

        String sql = translateToSql(q);

        int sub1Sub1JoinCount = countJoins(sql, "IV_SUB1_SUB1");
        assertEquals("IV_SUB1_SUB1 should be joined exactly once, but got SQL: " + sql,
                1, sub1Sub1JoinCount);

        int rootJoinCount = countJoins(sql, "IV_ROOT");
        assertEquals("IV_ROOT should not be joined (pruned), but got SQL: " + sql,
                0, rootJoinCount);
    }

    /**
     * If a path is extended past a redirect, the new JOIN sources its ON
     * clause from the redirect's target alias. Here, IvOther.IMPL redirects
     * to the IV_IMPL alias, then .OTHER1 adds a fresh FK join whose ON clause
     * references that alias. The pruning stage must keep the IV_IMPL node
     * alive even though no content-producing stage references its alias
     * directly, otherwise the generated SQL refers to an undefined alias.
     */
    @Test
    public void testRedirectTargetKeptWhenDescendantJoinSurvives() {
        ColumnSelect<String> q = ObjectSelect.query(IvOther.class)
                .column(IvOther.NAME)
                .where(IvOther.IMPL.dot(IvImpl.OTHER1).dot(IvOther.NAME).eq("x"));

        String sql = translateToSql(q);

        assertTrue("IV_IMPL must remain in FROM to back the descendant JOIN's source alias, but got SQL: " + sql,
                countJoins(sql, "IV_IMPL") >= 1);

        // Execute against the database; a missing alias would surface here as
        // a SQL exception and fail the test.
        q.select(context);
    }

    private String translateToSql(FluentSelect<?, ?> query) {
        DbAdapter adapter = dataNode.getAdapter();
        EntityResolver resolver = dataNode.getEntityResolver();
        return new DefaultSelectTranslator(query, adapter, resolver).getSql();
    }

    /**
     * Counts {@code JOIN <tableName>} occurrences. The trailing space avoids
     * prefix collisions between related table names (e.g. IV_IMPL vs
     * IV_IMPL_WITH_LOCK). Cayenne emits identifiers unquoted and uppercase
     * across all supported databases, so no regex is needed.
     */
    private static int countJoins(String sql, String tableName) {
        return Util.countMatches(sql, "JOIN " + tableName + " ");
    }
}
