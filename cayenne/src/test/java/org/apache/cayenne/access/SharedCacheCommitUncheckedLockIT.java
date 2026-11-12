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

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.PersistentObject;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.locking.FlattenedLockOrder;
import org.apache.cayenne.testdo.locking.InheritedLockMemo;
import org.apache.cayenne.testdo.locking.LockedPerson;
import org.apache.cayenne.testdo.locking.RedeclaredLockNote;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The first change of an object keeps the values read only of the lock attributes its commit is qualified with. A
 * lock attribute the commit doesn't check takes the value a peer committed since, like any other unmodified attribute,
 * so that the commit doesn't publish the older value to the shared cache.
 */
public class SharedCacheCommitUncheckedLockIT {

    private static final int PEER_REV = 5;

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.LOCKING_PROJECT);

    private TableHelper tLockedDoc;
    private TableHelper tLockedParty;
    private TableHelper tLockedPerson;
    private TableHelper tOrder;
    private TableHelper tOrderExt;

    @BeforeEach
    public void setUp() {
        tLockedDoc = env.table("LOCKED_DOC").setColumns("ID", "KIND", "NAME", "REV")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.INTEGER);
        tLockedParty = env.table("LOCKED_PARTY").setColumns("ID", "TYPE", "NAME")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.VARCHAR);
        tLockedPerson = env.table("LOCKED_PERSON").setColumns("ID", "REV")
                .setColumnTypes(Types.INTEGER, Types.INTEGER);
        tOrder = env.table("FLATTENED_LOCK_ORDER").setColumns("ID", "NAME")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR);
        tOrderExt = env.table("FLATTENED_LOCK_ORDER_EXT").setColumns("ID", "REV")
                .setColumnTypes(Types.INTEGER, Types.INTEGER);
    }

    /**
     * The sub-entity only inherits its super-entity's optimistic lock type, and its commits are not qualified.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 3)
    public void inheritedLockTypeTakesPeerValue(Integer readRev) throws SQLException {
        tLockedDoc.insert(1, "M", "n0", readRev);

        assertFirstChangeTakesPeerRev(InheritedLockMemo.class, tLockedDoc);
    }

    /**
     * The lock attribute is on the sub-table of a vertical inheritance sub-entity.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 3)
    public void subTableLockAttributeTakesPeerValue(Integer readRev) throws SQLException {
        tLockedParty.insert(1, "P", "n0");
        tLockedPerson.insert(1, readRev);

        assertFirstChangeTakesPeerRev(LockedPerson.class, tLockedPerson);
    }

    /**
     * The lock attribute is flattened, on another table than the entity's own.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 3)
    public void flattenedLockAttributeTakesPeerValue(Integer readRev) throws SQLException {
        tOrder.insert(1, "n0");
        tOrderExt.insert(1, readRev);

        assertFirstChangeTakesPeerRev(FlattenedLockOrder.class, tOrderExt);
    }

    /**
     * The sub-entity declares the optimistic lock type itself, so its commit checks the inherited lock attribute
     * against the value read.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 3)
    public void redeclaredLockTypeKeepsReadValue(Integer readRev) throws SQLException {
        tLockedDoc.insert(1, "N", "n0", readRev);

        ObjectContext context1 = env.runtime().newContext();
        ObjectContext context2 = env.runtime().newContext();
        RedeclaredLockNote note1 = ObjectSelect.query(RedeclaredLockNote.class).selectOne(context1);
        RedeclaredLockNote note2 = ObjectSelect.query(RedeclaredLockNote.class).selectOne(context2);

        note2.setRev(PEER_REV);
        context2.commitChanges();

        note1.setName("changed");
        assertEquals(readRev, note1.getRev());
        assertThrows(OptimisticLockException.class, context1::commitChanges);
    }

    /**
     * A peer sets the lock attribute "rev" and commits, then an object that read the row before changes another
     * attribute and commits. The object, and an object read through the shared cache afterwards, hold the peer's
     * value, so that a later change of the attribute is written.
     */
    private <T extends PersistentObject> void assertFirstChangeTakesPeerRev(Class<T> type, TableHelper revTable)
            throws SQLException {
        ObjectContext context1 = env.runtime().newContext();
        ObjectContext context2 = env.runtime().newContext();
        T object1 = ObjectSelect.query(type).selectOne(context1);
        T object2 = ObjectSelect.query(type).selectOne(context2);

        object2.writeProperty("rev", PEER_REV);
        context2.commitChanges();

        object1.writeProperty("name", "changed");
        context1.commitChanges();
        assertEquals(PEER_REV, object1.readProperty("rev"));
        assertEquals(PEER_REV, revTable.getInt("REV"));

        ObjectContext context3 = env.runtime().newContext();
        T object3 = context3.localObject(object1);
        assertEquals(PEER_REV, object3.readProperty("rev"));

        object3.writeProperty("rev", null);
        context3.commitChanges();
        assertNull(revTable.getObject("REV"));
    }
}
