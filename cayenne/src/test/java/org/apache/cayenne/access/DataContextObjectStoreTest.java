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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.cayenne.DataChannel;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.map.EntityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DataContextObjectStoreTest {

    /**
     * Enough registered objects that a hash-ordered map is overwhelmingly unlikely to iterate them in
     * registration order by coincidence.
     */
    private static final int ORDER_PROBE_SIZE = 32;

    private DataContextObjectStore objectStore;

    @BeforeEach
    public void before() {
        DataRowStore sharedCache = mock(DataRowStore.class);
        this.objectStore = DataContext.builder(mock(DataChannel.class))
                .snapshotCache(sharedCache)
                .objectMap(new HashMap<>())
                .build()
                .getObjectStore();
    }

    @Test
    public void registerObject() {

        ObjectId id = ObjectId.of("E1", "ID", 500);
        Persistent object = mock(Persistent.class);

        objectStore.registerObject(id, object);
        assertSame(object, objectStore.getObject(id));
    }

    @Test
    public void unregisterObject() {

        ObjectId id = ObjectId.of("E1", "ID", 500);
        Persistent object = mock(Persistent.class);

        objectStore.registerObject(id, object);
        Object unregistered = objectStore.unregisterObject(id);
        assertSame(object, unregistered);

        verify(object, times(0)).setObjectId(null);
        verify(object).setObjectContext(null);
        verify(object).setPersistenceState(PersistenceState.TRANSIENT);    }

    /**
     * The flush walks object diffs in the order {@code changes} yields them, so that order decides which
     * object's walk observes the other's bookkeeping. Registration order is the only order that is a property
     * of the objects being flushed rather than of their {@code ObjectId} hashes.
     */
    @Test
    public void changesAreIteratedInRegistrationOrder() {

        List<ObjectId> registered = new ArrayList<>(ORDER_PROBE_SIZE);
        for (int i = 0; i < ORDER_PROBE_SIZE; i++) {
            ObjectId id = ObjectId.of("E1", "ID", i);
            objectStore.registerObject(id, newObject(id));
            objectStore.registerDiff(id, null);
            registered.add(id);
        }

        assertEquals(registered, new ArrayList<>(objectStore.getChangesByObjectId().keySet()),
                "diffs must be walked in registration order, not in ObjectId hash order");
    }

    private Persistent newObject(ObjectId id) {
        EntityResolver entityResolver = mock(EntityResolver.class);
        ObjectContext context = mock(ObjectContext.class);
        when(context.getEntityResolver()).thenReturn(entityResolver);

        Persistent object = mock(Persistent.class);
        when(object.getObjectId()).thenReturn(id);
        when(object.getObjectContext()).thenReturn(context);
        // NEW keeps ObjectDiff's constructor out of the optimistic-locking snapshot branch, which would
        // need a mapped ObjEntity behind the resolver
        when(object.getPersistenceState()).thenReturn(PersistenceState.NEW);
        return object;
    }
}
