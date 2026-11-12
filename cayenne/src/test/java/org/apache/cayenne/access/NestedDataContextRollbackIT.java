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

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NestedDataContextRollbackIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT);

    @Test
    public void rollbackChanges() {
        ObjectContext child1 = env.runtime().newContext(env.context());

        assertFalse(env.context().hasChanges());
        assertFalse(child1.hasChanges());

        env.context().newObject(Artist.class);
        child1.newObject(Artist.class);

        assertTrue(env.context().hasChanges());
        assertTrue(child1.hasChanges());

        child1.rollbackChanges();
        assertFalse(env.context().hasChanges());
        assertFalse(child1.hasChanges());
    }

    @Test
    public void rollbackChangesLocally() {
        ObjectContext child1 = env.runtime().newContext(env.context());

        assertFalse(env.context().hasChanges());
        assertFalse(child1.hasChanges());

        env.context().newObject(Artist.class);
        child1.newObject(Artist.class);

        assertTrue(env.context().hasChanges());
        assertTrue(child1.hasChanges());

        child1.rollbackChangesLocally();
        assertTrue(env.context().hasChanges());
        assertFalse(child1.hasChanges());
    }

    // a rolled back new object is unregistered, so that its id resolves to the row once there is one
    @Test
    public void rollbackChangesLocally_NewObjectUnregistered() throws SQLException {
        DataContext child = (DataContext) env.runtime().newContext(env.context());
        ObjectId id = ObjectId.of("Artist", Artist.ARTIST_ID_PK_COLUMN, 1);
        Artist rolledBack = new Artist();
        rolledBack.setObjectId(id);
        child.registerNewObject(rolledBack);
        rolledBack.setArtistName("rolled back");

        child.rollbackChangesLocally();

        assertNull(child.getObjectStore().getObject(id));
        assertEquals(PersistenceState.TRANSIENT, rolledBack.getPersistenceState());
        assertNull(rolledBack.getObjectContext());
        assertNull(rolledBack.getObjectId());
        assertNull(child.objectForPK(id));

        env.table("ARTIST").setColumns("ARTIST_ID", "ARTIST_NAME").insert(1, "inserted");

        Persistent resolved = child.objectForPK(id);
        assertNotSame(rolledBack, resolved);
        assertSame(child, resolved.getObjectContext());
        assertEquals(PersistenceState.COMMITTED, resolved.getPersistenceState());
        assertEquals("inserted", ((Artist) resolved).getArtistName());
    }
}
