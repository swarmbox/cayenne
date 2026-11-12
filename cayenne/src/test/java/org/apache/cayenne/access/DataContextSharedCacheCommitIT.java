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
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.event.SnapshotEvent;
import org.apache.cayenne.event.DefaultEventManager;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.test.parallel.ParallelTestContainer;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Commits of contexts sharing a snapshot cache keep the changes other contexts made to the columns they did not write.
 */
public class DataContextSharedCacheCommitIT {

    private static final String TITLE = "PAINTING_TITLE";
    private static final String DESCRIPTION = "PAINTING_DESCRIPTION";

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT);

    private TableHelper tPainting;
    private DefaultEventManager eventManager;
    private DataRowStore cache;
    private final List<SnapshotEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        tPainting = env.table("PAINTING", "PAINTING_ID", TITLE, DESCRIPTION);
        tPainting.insert(1, "t0", "d0");

        eventManager = new DefaultEventManager();
        cache = new DataRowStore("commitTest", DefaultDataRowStoreFactory.SNAPSHOT_CACHE_SIZE_DEFAULT, eventManager);
        eventManager.addListener(events, SnapshotEvent.class, List::add, cache.getSnapshotEventSubject());
    }

    @AfterEach
    public void tearDown() {
        eventManager.shutdown();
    }

    /**
     * A peer changes the title of a row evicted from the cache, then a context that had already changed the
     * description commits.
     */
    @Test
    public void commitOfModifiedObjectKeepsPeerChange_EvictedRow() throws Exception {
        DataContext context1 = context(true);
        DataContext context2 = context(true);

        Painting p1 = painting(context1);
        p1.setPaintingDescription("d1");
        Painting p2 = painting(context2);
        cache.forgetSnapshot(p1.getObjectId());

        p2.setPaintingTitle("t1");
        context2.commitChanges();

        context1.commitChanges();
        assertEquals(Map.of(DESCRIPTION, "d1"), lastModifiedDiff(p1.getObjectId()));
        assertCommitted(p1, p2);
    }

    /**
     * A peer changes the title of a row evicted from the cache, then a context that held the object unchanged changes
     * the description and commits.
     */
    @Test
    public void commitOfCommittedObjectKeepsPeerChange_EvictedRow() throws Exception {
        DataContext context1 = context(true);
        DataContext context2 = context(true);

        Painting p1 = painting(context1);
        Painting p2 = painting(context2);
        cache.forgetSnapshot(p1.getObjectId());

        p2.setPaintingTitle("t1");
        context2.commitChanges();

        p1.setPaintingDescription("d1");
        context1.commitChanges();
        assertCommitted(p1, p2);
    }

    /**
     * The committing context has not processed the event of the peer change yet, as when it commits right after the
     * peer. A context that doesn't listen to the events is in that state for good.
     */
    @Test
    public void commitOfModifiedObjectKeepsPeerChange_EventNotProcessed() throws Exception {
        DataContext context1 = context(false);
        DataContext context2 = context(true);

        Painting p1 = painting(context1);
        p1.setPaintingDescription("d1");
        Painting p2 = painting(context2);

        p2.setPaintingTitle("t1");
        context2.commitChanges();

        context1.commitChanges();
        assertCommitted(p1, p2);
    }

    @Test
    public void firstChangeMergesNewerCachedSnapshot() throws Exception {
        DataContext context1 = context(false);
        DataContext context2 = context(true);

        Painting p1 = painting(context1);
        Painting p2 = painting(context2);
        p2.setPaintingTitle("t1");
        context2.commitChanges();
        assertEquals("t0", p1.getPaintingTitle());

        p1.setPaintingDescription("d1");
        assertEquals("t1", p1.getPaintingTitle());

        context1.commitChanges();
        assertCommitted(p1, p2);
    }

    private DataContext context(boolean syncWithSnapshotCache) {
        return DataContext.builder(env.runtime().getDataDomain())
                .snapshotCache(cache)
                .objectMap(new HashMap<>())
                .syncWithSnapshotCache(syncWithSnapshotCache)
                .build();
    }

    private static Painting painting(DataContext context) {
        return ObjectSelect.query(Painting.class).selectOne(context);
    }

    private Map<String, Object> lastModifiedDiff(ObjectId id) {
        Map<String, Object> diff = null;
        for (SnapshotEvent event : events) {
            Map<String, Object> eventDiff = event.getModifiedDiffs().get(id);
            if (eventDiff != null) {
                diff = eventDiff;
            }
        }
        return diff;
    }

    // the title "t1" committed by the peer and the description "d1" committed after it are both everywhere
    private void assertCommitted(Painting committer, Painting peer) throws Exception {
        assertEquals("t1", tPainting.getString(TITLE));
        assertEquals("d1", tPainting.getString(DESCRIPTION));

        DataRow cached = cache.getCachedSnapshot(committer.getObjectId());
        assertEquals("t1", cached.get(TITLE));
        assertEquals("d1", cached.get(DESCRIPTION));

        assertEquals("t1", committer.getPaintingTitle());

        new ParallelTestContainer() {

            @Override
            protected void assertResult() {
                assertEquals("d1", peer.getPaintingDescription());
            }
        }.runTest(3000);
        assertEquals("t1", peer.getPaintingTitle());
    }
}
