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
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.PrefetchTreeNode;
import org.apache.cayenne.query.SelectById;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.testdo.testmap.PaintingInfo;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that prefetching also populates the inverse to-one on the fetched
 * child, so downstream reads of the inverse do not fire extra queries.
 */
public class ReversePrefetchIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT);
    
    private TableHelper tArtist;
    private TableHelper tPainting;
    private TableHelper tPaintingInfo;

    @BeforeEach
    public void setUp() throws Exception {
        tArtist = env.table("ARTIST", "ARTIST_ID", "ARTIST_NAME");
        tPainting = env.table("PAINTING", "PAINTING_ID", "ARTIST_ID", "PAINTING_TITLE");
        tPaintingInfo = env.table("PAINTING_INFO", "PAINTING_ID", "TEXT_REVIEW");
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
                .select(env.context());

        assertEquals(2, paintings.size());
        env.runWithQueriesBlocked(() -> {
            for (Painting p : paintings) {
                PaintingInfo info = (PaintingInfo) p.readPropertyDirectly(
                        Painting.TO_PAINTING_INFO.getName());
                assertNotNull(info);
                assertEquals(PersistenceState.COMMITTED, info.getPersistenceState());

                Object reverse = info.readPropertyDirectly(PaintingInfo.PAINTING.getName());
                assertFalse(reverse instanceof Fault, "reverse to-one should not be a Fault after prefetch");
                assertNotNull(reverse);
                assertSame(p, reverse, "reverse should point back to the owning parent");
            }
        });
    }

    @Test
    public void reverseToOnePopulatedJoint() throws Exception {
        runAndAssertPaintingInfoReversePopulated(Painting.TO_PAINTING_INFO.joint());
    }

    @Test
    public void reverseToOnePopulatedDisjoint() throws Exception {
        runAndAssertPaintingInfoReversePopulated(Painting.TO_PAINTING_INFO.disjoint());
    }

    @Test
    public void reverseToOnePopulatedDisjointById() throws Exception {
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
                .select(env.context());

        assertEquals(2, artists.size());
        env.runWithQueriesBlocked(() -> {
            for (Artist a : artists) {
                List<Painting> paintings = (List<Painting>) a.readPropertyDirectly(
                        Artist.PAINTING_ARRAY.getName());
                assertNotNull(paintings);
                assertEquals(2, paintings.size());
                for (Painting p : paintings) {
                    Object reverse = p.readPropertyDirectly(Painting.TO_ARTIST.getName());
                    assertFalse(reverse instanceof Fault, "reverse to-one should not be a Fault after prefetch");
                    assertNotNull(reverse);
                    assertSame(a, reverse, "each painting's toArtist must match the owning artist (regression guard for cross-parent mixups)");
                }
            }
        });
    }

    @Test
    public void toManyReverseToOnePopulatedJoint() throws Exception {
        runAndAssertPaintingToArtistReversePopulated(Artist.PAINTING_ARRAY.joint());
    }

    @Test
    public void toManyReverseToOnePopulatedDisjoint() throws Exception {
        runAndAssertPaintingToArtistReversePopulated(Artist.PAINTING_ARRAY.disjoint());
    }

    @Test
    public void toManyReverseToOnePopulatedDisjointById() throws Exception {
        runAndAssertPaintingToArtistReversePopulated(Artist.PAINTING_ARRAY.disjointById());
    }

    /**
     * If the child's reverse has pending user changes, the prefetch must not
     * overwrite them with the DB-driven parent.
     */
    @Test
    public void ModifiedReverseNotOverwritten() throws Exception {
        tArtist.insert(1, "a1");
        tArtist.insert(2, "a2");
        tPainting.insert(1, 1, "p1");

        Painting p1 = SelectById.queryId(Painting.class, 1).selectOne(env.context());
        Artist a2 = SelectById.queryId(Artist.class, 2).selectOne(env.context());
        p1.setToArtist(a2);
        assertEquals(PersistenceState.MODIFIED, p1.getPersistenceState());

        // p1's DB row still has ARTIST_ID = 1, so the disjoint prefetch of
        // a1.paintingArray pulls p1 back in and invokes linkToParent(p1, a1).
        Artist a1 = ObjectSelect.query(Artist.class, Artist.ARTIST_NAME.eq("a1"))
                .prefetch(Artist.PAINTING_ARRAY.disjoint())
                .selectOne(env.context());

        assertNotNull(a1);
        assertSame(a2, p1.getToArtist(), "pending user modification of toArtist must survive the prefetch");
    }
}
