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
import org.apache.cayenne.event.DefaultEventManager;
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

import java.sql.Types;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A commit that brings its object up to date with a newer cached row leaves the to-ones that were never resolved as
 * faults, rather than resolving them inside the commit. IvImpl's to-ones are flattened through its own table, so each
 * one would take a query.
 */
public class SharedCacheCommitToOneFaultIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    private DefaultEventManager eventManager;
    private DataRowStore cache;

    @BeforeEach
    public void setUp() throws Exception {
        TableHelper tOther = env.table("IV_OTHER", "ID", "NAME");
        TableHelper tBase = env.table("IV_BASE", "ID", "NAME", "TYPE");
        TableHelper tImpl = env.table("IV_IMPL").setColumns("ID", "ATTR1", "OTHER1_ID", "OTHER2_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.INTEGER);

        tOther.insert(1, "o1");
        tOther.insert(2, "o2");
        tBase.insert(10, "impl", "I");
        tImpl.insert(10, "a1", 1, 2);

        eventManager = new DefaultEventManager();
        cache = new DataRowStore("commitTest", DefaultDataRowStoreFactory.SNAPSHOT_CACHE_SIZE_DEFAULT, eventManager);
    }

    @AfterEach
    public void tearDown() {
        eventManager.shutdown();
    }

    @Test
    public void commitAfterNewerCachedRowSendsOnlyItsUpdate() {
        DataContext context1 = context(false);
        DataContext context2 = context(true);

        IvImpl impl1 = impl(context1);
        impl1.setAttr1("a2");

        IvImpl impl2 = impl(context2);
        impl2.setName("peer");
        context2.commitChanges();

        int commitQueries = env.runWithQueryCounter(context1::commitChanges);

        assertAll(
                () -> assertEquals(1, commitQueries),
                () -> assertEquals("peer", impl1.getName()),
                () -> assertEquals(1, Cayenne.intPKForObject(impl1.getOther1())),
                () -> assertEquals(2, Cayenne.intPKForObject(impl1.getOther2())));
    }

    @Test
    public void unresolvedToOneRepointedByPeerResolvesToPeerTarget() {
        DataContext context1 = context(false);
        DataContext context2 = context(true);

        IvImpl impl1 = impl(context1);
        impl1.setAttr1("a2");

        IvImpl impl2 = impl(context2);
        impl2.setOther1(SelectById.queryId(IvOther.class, 2).selectOne(context2));
        context2.commitChanges();

        context1.commitChanges();
        assertEquals(2, Cayenne.intPKForObject(impl1.getOther1()));
    }

    private DataContext context(boolean syncWithSnapshotCache) {
        return DataContext.builder(env.runtime().getDataDomain())
                .snapshotCache(cache)
                .objectMap(new HashMap<>())
                .syncWithSnapshotCache(syncWithSnapshotCache)
                .build();
    }

    private static IvImpl impl(DataContext context) {
        return SelectById.queryId(IvImpl.class, 10).selectOne(context);
    }
}
