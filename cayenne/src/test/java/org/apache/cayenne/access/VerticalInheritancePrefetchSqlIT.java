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
import org.apache.cayenne.access.flush.operation.DbRowOpSorter;
import org.apache.cayenne.access.flush.operation.GraphBasedDbRowOpSorter;
import org.apache.cayenne.access.translator.TranslatedStatement;
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the SQL of prefetches over a to-one relationship of a vertically inherited sub-entity whose FK lives in
 * the sub-entity table (IvImpl.OTHER1, db-path "impl.other1"). The parent rows already carry that FK, so the
 * prefetch query must not join back through the sub-entity and super-entity tables to relate its rows to the
 * parents.
 *
 * @link https://issues.apache.org/jira/browse/CAY-2909
 */
public class VerticalInheritancePrefetchSqlIT {

    private static final RecordingSQLLogger SQL_LOGGER = new RecordingSQLLogger();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT)
            .withExtraModules(
                    binder -> binder.bind(DbRowOpSorter.class).to(GraphBasedDbRowOpSorter.class),
                    binder -> binder.bind(SQLLogger.class).toInstance(SQL_LOGGER));

    @BeforeEach
    public void createTestData() throws SQLException {
        TableHelper ivOtherTable = env.table("IV_OTHER", "ID", "NAME");
        TableHelper ivBaseTable = env.table("IV_BASE", "ID", "NAME", "TYPE");
        TableHelper ivImplTable = env.table("IV_IMPL").setColumns("ID", "ATTR1", "OTHER1_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);

        ivOtherTable.insert(1, "other1");
        ivOtherTable.insert(2, "other2");

        // two impls share other1, one points to other2, one has no other1
        for (int id = 1; id <= 4; id++) {
            ivBaseTable.insert(id, "impl" + id, "I");
        }
        ivImplTable.insert(1, "attr1", 1);
        ivImplTable.insert(2, "attr2", 1);
        ivImplTable.insert(3, "attr3", 2);
        ivImplTable.insert(4, "attr4", null);

        SQL_LOGGER.selects.clear();
    }

    @Test
    public void disjointByIdPrefetchSelectsOnlyTargetTable() {
        List<IvImpl> impls = ObjectSelect.query(IvImpl.class)
                .prefetch(IvImpl.OTHER1.disjointById())
                .orderBy(IvImpl.NAME.asc())
                .select(env.context());

        // IV_OTHER's own flattened "impl" FK legitimately left-joins IV_IMPL, so look for the reverse join on OTHER1_ID
        LoggedSelect prefetch = otherPrefetch();
        assertFalse(prefetch.sql().contains("OTHER1_ID"), "Prefetch joins back to IV_IMPL: " + prefetch.sql());
        assertFalse(prefetch.sql().contains("IV_BASE"), "Prefetch joins IV_BASE: " + prefetch.sql());
        assertEquals(2, prefetch.rowCount(), "Prefetch must fetch each IV_OTHER row once: " + prefetch.sql());

        assertOthersResolved(impls);
    }

    @Test
    public void disjointPrefetchFetchesEachTargetOnce() {
        List<IvImpl> impls = ObjectSelect.query(IvImpl.class)
                .prefetch(IvImpl.OTHER1.disjoint())
                .orderBy(IvImpl.NAME.asc())
                .select(env.context());

        LoggedSelect prefetch = otherPrefetch();
        assertEquals(2, prefetch.rowCount(), "Prefetch must fetch each IV_OTHER row once: " + prefetch.sql());

        assertOthersResolved(impls);
    }

    private LoggedSelect otherPrefetch() {
        List<LoggedSelect> prefetches = SQL_LOGGER.selects.stream()
                .filter(select -> select.sql().contains("FROM IV_OTHER"))
                .toList();
        assertEquals(1, prefetches.size(), "Expected a single IV_OTHER prefetch: " + SQL_LOGGER.selects);
        return prefetches.getFirst();
    }

    private void assertOthersResolved(List<IvImpl> impls) {
        assertEquals(4, impls.size());
        env.runWithQueriesBlocked(() -> {
            assertEquals(1, Cayenne.intPKForObject(impls.get(0).getOther1()));
            assertEquals(1, Cayenne.intPKForObject(impls.get(1).getOther1()));
            assertEquals(2, Cayenne.intPKForObject(impls.get(2).getOther1()));
            assertNull(impls.get(3).getOther1());
        });
    }

    private record LoggedSelect(String sql, int rowCount) {
    }

    private static class RecordingSQLLogger implements SQLLogger {

        final List<LoggedSelect> selects = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void logSelect(TranslatedStatement statement, int rowCount, long durationMillis) {
            selects.add(new LoggedSelect(statement.sql(), rowCount));
        }

        @Override
        public void logUpdate(TranslatedStatement statement, int rowCount, List<? extends Map<String, ?>> generatedKeys,
                              long durationMillis) {
        }

        @Override
        public void logQueryError(TranslatedStatement statement, Throwable error, long durationMillis) {
        }

        @Override
        public void logAlsoSelect(int rowCount) {
        }

        @Override
        public void logAlsoUpdate(int rowCount) {
        }

        @Override
        public void logTransactionStart() {
        }

        @Override
        public void logTransactionCommit() {
        }

        @Override
        public void logTransactionRollback() {
        }

        @Override
        public void logMessage(String message) {
        }
    }
}
