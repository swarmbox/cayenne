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

import org.apache.cayenne.CoreModule;
import org.apache.cayenne.DataRow;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.event.SnapshotEvent;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.event.EventManager;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A DataRowStore subclass that overrides {@link DataRowStore#processSnapshotChanges(Object, Map, Collection,
 * Collection, Collection)} sees every commit, and the values a commit wrote reach the processing of that commit only.
 */
public class DataRowStoreSubclassIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT)
            .withExtraModules(
                    b -> b.bind(DataRowStoreFactory.class).to(RecordingStore.Factory.class),
                    b -> CoreModule.extend(b).syncContexts());

    private DataContext context;

    @BeforeEach
    public void setUp() throws Exception {
        RecordingStore.CALLS.clear();
        env.table("ARTIST", "ARTIST_ID", "ARTIST_NAME").insert(1, "a1");
        context = env.context();
    }

    @Test
    public void updateInsertAndDeleteCommitsAreSeen() {
        Artist artist = ObjectSelect.query(Artist.class).selectOne(context);
        RecordingStore.CALLS.clear();

        artist.setArtistName("a1-updated");
        context.commitChanges();
        assertEquals(List.of(new Call(Set.of(artist.getObjectId()), Set.of(), Set.of())), RecordingStore.CALLS);

        RecordingStore.CALLS.clear();
        Artist newArtist = context.newObject(Artist.class);
        newArtist.setArtistName("a2");
        context.commitChanges();
        assertEquals(List.of(new Call(Set.of(newArtist.getObjectId()), Set.of(), Set.of())), RecordingStore.CALLS);

        RecordingStore.CALLS.clear();
        context.deleteObject(artist);
        context.commitChanges();
        assertEquals(List.of(new Call(Set.of(), Set.of(artist.getObjectId()), Set.of())), RecordingStore.CALLS);
    }

    /**
     * The values a commit wrote reach only the processing of that commit: a later call on the same thread, for the
     * same row, again not cached, announces nothing for it.
     */
    @Test
    public void writtenValuesOfCommitDoNotReachLaterCall() {
        Artist artist = ObjectSelect.query(Artist.class).selectOne(context);
        ObjectId id = artist.getObjectId();

        DataRowStore cache = context.getObjectStore().getDataRowCache();
        List<SnapshotEvent> events = new CopyOnWriteArrayList<>();
        cache.getEventManager().addListener(events, SnapshotEvent.class, List::add, cache.getSnapshotEventSubject());

        cache.forgetSnapshot(id);
        artist.setArtistName("a1-updated");
        context.commitChanges();

        assertEquals(1, events.size());
        assertEquals(Map.of("ARTIST_NAME", "a1-updated"), events.get(0).getModifiedDiffs().get(id));

        DataRow committed = cache.getCachedSnapshot(id);
        cache.forgetSnapshot(id);
        DataRow later = committed.merged(Map.of(), committed.getVersion() + 1);
        cache.processSnapshotChanges(this, Map.of(id, later), List.of(), List.of(), List.of());

        // a snapshot with no cached predecessor and no written values is not announced
        assertEquals(1, events.size(), events::toString);
    }

    record Call(Set<ObjectId> updated, Set<ObjectId> deleted, Set<ObjectId> invalidated) {
    }

    public static class RecordingStore extends DataRowStore {

        static final List<Call> CALLS = new CopyOnWriteArrayList<>();

        RecordingStore(String name, EventManager eventManager) {
            super(name, DefaultDataRowStoreFactory.SNAPSHOT_CACHE_SIZE_DEFAULT, eventManager);
        }

        @Override
        public void processSnapshotChanges(
                Object postedBy,
                Map<ObjectId, DataRow> updatedSnapshots,
                Collection<ObjectId> deletedSnapshotIds,
                Collection<ObjectId> invalidatedSnapshotIds,
                Collection<ObjectId> indirectlyModifiedIds) {
            CALLS.add(new Call(
                    Set.copyOf(updatedSnapshots.keySet()),
                    Set.copyOf(deletedSnapshotIds),
                    Set.copyOf(invalidatedSnapshotIds)));
            super.processSnapshotChanges(
                    postedBy,
                    updatedSnapshots,
                    deletedSnapshotIds,
                    invalidatedSnapshotIds,
                    indirectlyModifiedIds);
        }

        public static class Factory implements DataRowStoreFactory {

            @Inject
            private EventManager eventManager;

            @Override
            public DataRowStore createDataRowStore(String name) {
                return new RecordingStore(name, eventManager);
            }
        }
    }
}
