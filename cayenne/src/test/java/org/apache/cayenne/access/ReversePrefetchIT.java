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

import org.apache.cayenne.Fault;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.query.SelectById;
import org.apache.cayenne.test.jdbc.DBHelper;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.testdo.testmap.PaintingInfo;
import org.apache.cayenne.unit.di.DataChannelInterceptor;
import org.apache.cayenne.unit.di.runtime.CayenneProjects;
import org.apache.cayenne.unit.di.runtime.RuntimeCase;
import org.apache.cayenne.unit.di.runtime.UseCayenneRuntime;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * Verifies that prefetching also populates the inverse to-one on the fetched
 * child, so downstream reads of the inverse do not fire extra queries.
 *
 * @since 5.0
 */
@UseCayenneRuntime(CayenneProjects.TESTMAP_PROJECT)
public class ReversePrefetchIT extends RuntimeCase {

    @Inject
    protected DataContext context;

    @Inject
    protected DBHelper dbHelper;

    @Inject
    protected DataChannelInterceptor queryInterceptor;

    private TableHelper tArtist;
    private TableHelper tPainting;
    private TableHelper tPaintingInfo;

    @Before
    public void setUp() throws Exception {
        tArtist = new TableHelper(dbHelper, "ARTIST");
        tArtist.setColumns("ARTIST_ID", "ARTIST_NAME");

        tPainting = new TableHelper(dbHelper, "PAINTING");
        tPainting.setColumns("PAINTING_ID", "ARTIST_ID", "PAINTING_TITLE");

        tPaintingInfo = new TableHelper(dbHelper, "PAINTING_INFO");
        tPaintingInfo.setColumns("PAINTING_ID", "TEXT_REVIEW");
    }

    // --- shared-PK 1:1 (Painting.toPaintingInfo / PaintingInfo.painting) ---

    private void createTwoPaintingsWithInfosDataSet() throws Exception {
        tArtist.insert(1, "a1");
        tPainting.insert(1, 1, "p1");
        tPainting.insert(2, 1, "p2");
        tPaintingInfo.insert(1, "red");
        tPaintingInfo.insert(2, "green");
    }

    private void runAndAssertPaintingInfoReversePopulated(PrefetchTreeNode prefetch) throws Exception {
        createTwoPaintingsWithInfosDataSet();

        List<Painting> paintings = ObjectSelect.query(Painting.class)
                .prefetch(prefetch)
                .select(context);

        assertEquals(2, paintings.size());
        queryInterceptor.runWithQueriesBlocked(() -> {
            for (Painting p : paintings) {
                PaintingInfo info = (PaintingInfo) p.readPropertyDirectly(
                        Painting.TO_PAINTING_INFO.getName());
                assertNotNull(info);
                assertEquals(PersistenceState.COMMITTED, info.getPersistenceState());

                Object reverse = info.readPropertyDirectly(PaintingInfo.PAINTING.getName());
                assertFalse("reverse to-one should not be a Fault after prefetch",
                        reverse instanceof Fault);
                assertNotNull(reverse);
                assertSame("reverse should point back to the owning parent", p, reverse);
            }
        });
    }

    @Test
    public void testReverseToOnePopulated_joint() throws Exception {
        runAndAssertPaintingInfoReversePopulated(Painting.TO_PAINTING_INFO.joint());
    }

    @Test
    public void testReverseToOnePopulated_disjoint() throws Exception {
        runAndAssertPaintingInfoReversePopulated(Painting.TO_PAINTING_INFO.disjoint());
    }

    @Test
    public void testReverseToOnePopulated_disjointById() throws Exception {
        runAndAssertPaintingInfoReversePopulated(Painting.TO_PAINTING_INFO.disjointById());
    }

    // --- FK to-many (Artist.paintingArray / Painting.toArtist) across multiple parents ---

    private void createTwoArtistsWithPaintingsDataSet() throws Exception {
        tArtist.insert(1, "a1");
        tArtist.insert(2, "a2");
        tPainting.insert(1, 1, "p1");
        tPainting.insert(2, 1, "p2");
        tPainting.insert(3, 2, "p3");
        tPainting.insert(4, 2, "p4");
    }

    @SuppressWarnings("unchecked")
    private void runAndAssertPaintingToArtistReversePopulated(PrefetchTreeNode prefetch) throws Exception {
        createTwoArtistsWithPaintingsDataSet();

        List<Artist> artists = ObjectSelect.query(Artist.class)
                .prefetch(prefetch)
                .select(context);

        assertEquals(2, artists.size());
        queryInterceptor.runWithQueriesBlocked(() -> {
            for (Artist a : artists) {
                List<Painting> paintings = (List<Painting>) a.readPropertyDirectly(
                        Artist.PAINTING_ARRAY.getName());
                assertNotNull(paintings);
                assertEquals(2, paintings.size());
                for (Painting p : paintings) {
                    Object reverse = p.readPropertyDirectly(Painting.TO_ARTIST.getName());
                    assertFalse("reverse to-one should not be a Fault after prefetch",
                            reverse instanceof Fault);
                    assertNotNull(reverse);
                    assertSame("each painting's toArtist must match the owning artist "
                            + "(regression guard for cross-parent mixups)", a, reverse);
                }
            }
        });
    }

    @Test
    public void testToManyReverseToOnePopulated_joint() throws Exception {
        runAndAssertPaintingToArtistReversePopulated(Artist.PAINTING_ARRAY.joint());
    }

    @Test
    public void testToManyReverseToOnePopulated_disjoint() throws Exception {
        runAndAssertPaintingToArtistReversePopulated(Artist.PAINTING_ARRAY.disjoint());
    }

    @Test
    public void testToManyReverseToOnePopulated_disjointById() throws Exception {
        runAndAssertPaintingToArtistReversePopulated(Artist.PAINTING_ARRAY.disjointById());
    }

    /**
     * If the child's reverse has pending user changes, the prefetch must not
     * overwrite them with the DB-driven parent.
     */
    @Test
    public void testModifiedReverseNotOverwritten() throws Exception {
        tArtist.insert(1, "a1");
        tArtist.insert(2, "a2");
        tPainting.insert(1, 1, "p1");

        Painting p1 = SelectById.queryId(Painting.class, 1).selectOne(context);
        Artist a2 = SelectById.queryId(Artist.class, 2).selectOne(context);
        p1.setToArtist(a2);
        assertEquals(PersistenceState.MODIFIED, p1.getPersistenceState());

        // p1's DB row still has ARTIST_ID = 1, so the disjoint prefetch of
        // a1.paintingArray pulls p1 back in and invokes linkToParent(p1, a1).
        Artist a1 = ObjectSelect.query(Artist.class, Artist.ARTIST_NAME.eq("a1"))
                .prefetch(Artist.PAINTING_ARRAY.disjoint())
                .selectOne(context);

        assertNotNull(a1);
        assertSame("pending user modification of toArtist must survive the prefetch",
                a2, p1.getToArtist());
    }
}
