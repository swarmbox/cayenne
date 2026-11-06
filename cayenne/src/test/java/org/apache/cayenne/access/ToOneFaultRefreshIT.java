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
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.access.translator.TranslatedStatement;
import org.apache.cayenne.log.SQLLogger;
import org.apache.cayenne.query.SelectById;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Resolving a to-one fault from the database doesn't refresh a target already registered in the context.
 * IvOther.impl is flattened, so its fault always runs a query.
 */
public class ToOneFaultRefreshIT {

    private static final RecordingSQLLogger SQL_LOGGER = new RecordingSQLLogger();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT)
            .withExtraModules(binder -> binder.bind(SQLLogger.class).toInstance(SQL_LOGGER));

    private TableHelper tBase;
    private TableHelper tImpl;

    private DataContext context;
    private IvImpl impl;
    private IvOther source;

    @BeforeEach
    public void createTestData() throws SQLException {
        TableHelper tOther = env.table("IV_OTHER").setColumns("ID", "NAME", "IMPL_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);
        tBase = env.table("IV_BASE", "ID", "NAME", "TYPE");
        tImpl = env.table("IV_IMPL").setColumns("ID", "ATTR1", "OTHER1_ID", "OTHER2_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.INTEGER);

        tOther.insert(1, "o1", null);
        tOther.insert(2, "o2", null);
        tBase.insert(10, "impl", "I");
        tImpl.insert(10, "a1", 1, 2);
        tOther.insert(3, "source", 10);

        context = env.context();
        impl = SelectById.queryId(IvImpl.class, 10).selectOne(context);
        assertEquals(1, Cayenne.intPKForObject(impl.getOther1()));
        assertEquals(2, Cayenne.intPKForObject(impl.getOther2()));
        source = SelectById.queryId(IvOther.class, 3).selectOne(context);
        SQL_LOGGER.selects.clear();
    }

    /**
     * Deletes the IV_IMPL and IV_OTHER rows, which reference each other: IvOther #3 points to impl #10, and impl #10
     * points to IvOther #1 and #2. The env's clean-up deletes one table at a time, so on a database whose test FKs
     * don't cascade, it would fail on these rows before every later test of this mapping.
     */
    @AfterEach
    public void deleteImplOtherCycle() throws SQLException {
        env.table("IV_OTHER", "IMPL_ID").update().set("IMPL_ID", null, Types.INTEGER).execute();
        env.table("IV_IMPL").deleteAll();
        env.table("IV_OTHER").deleteAll();
    }

    @Test
    public void resolvedToOnesOfTargetSurvive() {
        int resolveQueries = resolveFault();
        String resolveSql = takeSelects();

        int toOneQueries = env.runWithQueryCounter(() -> {
            assertEquals(1, Cayenne.intPKForObject(impl.getOther1()));
            assertEquals(2, Cayenne.intPKForObject(impl.getOther2()));
        });

        assertAll(
                () -> assertEquals(1, resolveQueries, () -> "Fault resolution queries: " + resolveSql),
                () -> assertEquals(0, toOneQueries, () -> "Target to-ones were reset to faults: " + takeSelects()));
    }

    @Test
    public void committedAttributesOfTargetAreNotRefreshed() throws SQLException {
        tBase.update().set("NAME", "impl-db").where("ID", 10).execute();
        tImpl.update().set("ATTR1", "a1-db").where("ID", 10).execute();

        int resolveQueries = resolveFault();

        assertAll(
                () -> assertEquals(1, resolveQueries, () -> "Fault resolution queries: " + takeSelects()),
                () -> assertEquals(PersistenceState.COMMITTED, impl.getPersistenceState()),
                () -> assertEquals("impl", impl.getName()),
                () -> assertEquals("a1", impl.getAttr1()));
    }

    @Test
    public void committedToOneOfTargetIsNotRefreshed() throws SQLException {
        tImpl.update().set("OTHER1_ID", 2).where("ID", 10).execute();

        int resolveQueries = resolveFault();
        String resolveSql = takeSelects();

        int[] other1 = new int[1];
        int toOneQueries = env.runWithQueryCounter(() -> other1[0] = Cayenne.intPKForObject(impl.getOther1()));

        assertAll(
                () -> assertEquals(1, resolveQueries, () -> "Fault resolution queries: " + resolveSql),
                () -> assertEquals(1, other1[0]),
                () -> assertEquals(0, toOneQueries, () -> "Target to-one was reset to a fault: " + takeSelects()));
    }

    @Test
    public void uncommittedAttributeOfTargetSurvives() throws SQLException {
        impl.setAttr1("a1-local");
        tBase.update().set("NAME", "impl-db").where("ID", 10).execute();
        tImpl.update().set("ATTR1", "a1-db").where("ID", 10).execute();

        int resolveQueries = resolveFault();
        String resolveSql = takeSelects();

        int state = impl.getPersistenceState();
        String attr1 = impl.getAttr1();
        String name = impl.getName();
        int toOneQueries = env.runWithQueryCounter(() -> {
            assertEquals(1, Cayenne.intPKForObject(impl.getOther1()));
            assertEquals(2, Cayenne.intPKForObject(impl.getOther2()));
        });
        String toOneSql = takeSelects();

        context.commitChanges();

        assertAll(
                () -> assertEquals(1, resolveQueries, () -> "Fault resolution queries: " + resolveSql),
                () -> assertEquals(PersistenceState.MODIFIED, state),
                () -> assertEquals("a1-local", attr1),
                () -> assertEquals("impl", name),
                () -> assertEquals(0, toOneQueries, () -> "Target to-ones were reset to faults: " + toOneSql),
                () -> assertEquals("a1-local", tImpl.getString("ATTR1")),
                () -> assertEquals("impl-db", tBase.getString("NAME")));
    }

    @Test
    public void uncommittedToOneOfTargetSurvives() throws SQLException {
        IvOther o2 = impl.getOther2();
        impl.setOther1(o2);

        int resolveQueries = resolveFault();
        String resolveSql = takeSelects();

        int state = impl.getPersistenceState();
        IvOther[] toOnes = new IvOther[2];
        int toOneQueries = env.runWithQueryCounter(() -> {
            toOnes[0] = impl.getOther1();
            toOnes[1] = impl.getOther2();
        });
        String toOneSql = takeSelects();

        context.commitChanges();

        assertAll(
                () -> assertEquals(1, resolveQueries, () -> "Fault resolution queries: " + resolveSql),
                () -> assertEquals(PersistenceState.MODIFIED, state),
                () -> assertSame(o2, toOnes[0]),
                () -> assertSame(o2, toOnes[1]),
                () -> assertEquals(0, toOneQueries, () -> "Target to-ones were reset to faults: " + toOneSql),
                () -> assertEquals(2, tImpl.getInt("OTHER1_ID")));
    }

    private int resolveFault() {
        SQL_LOGGER.selects.clear();
        return env.runWithQueryCounter(() -> assertSame(impl, source.getImpl()));
    }

    private static String takeSelects() {
        String selects = SQL_LOGGER.selects.toString();
        SQL_LOGGER.selects.clear();
        return selects;
    }

    private static class RecordingSQLLogger implements SQLLogger {

        final List<String> selects = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void logSelect(TranslatedStatement statement, int rowCount, long durationMillis) {
            selects.add(statement.sql());
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
