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

package org.apache.cayenne.access.translator.select;

import org.apache.cayenne.dba.DbAdapter;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.Select;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.testdo.testmap.PaintingInfo;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the round-trip join redirect in {@code JoinRedirectProcessor}
 * for non-inheritance scenarios: FK round-trips (A -> B via FK -> A via
 * reverse FK) and shared-PK round-trips (A -> B shared PK -> A).
 */
public class RoundTripRedirectIT {

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

    /**
     * With an OUTER parent hop, the INNER FK back-join would filter paintings
     * with no matching artist. The redirect would skip that filter, so it
     * must be refused.
     */
    @Test
    public void fkRoundTripNotRedirectedWithOuterJoin() {
        ObjectSelect<Artist> q = ObjectSelect.query(Artist.class)
                .where(Artist.PAINTING_ARRAY.outer().dot(Painting.TO_ARTIST)
                        .dot(Artist.ARTIST_NAME).eq("test"));

        String sql = translateToSql(q);

        assertTrue(sql.contains("JOIN ARTIST "), "ARTIST should be joined when parent is LEFT OUTER, but got SQL: " + sql);
    }

    /**
     * All-INNER shared-PK round-trip (PAINTING -> PAINTING_INFO -> PAINTING)
     * collapses to the root alias -- pins the baseline for the outer-parent
     * carve-out below.
     */
    @Test
    public void innerSharedPkRoundTripRedirected() {
        ObjectSelect<Painting> q = ObjectSelect.query(Painting.class)
                .where(Painting.TO_PAINTING_INFO
                        .dot(PaintingInfo.PAINTING)
                        .dot(Painting.PAINTING_TITLE).eq("test"));

        String sql = translateToSql(q);

        // Root PAINTING is in FROM; the back-hop should be redirected to it,
        // leaving no "JOIN PAINTING " in the output.
        assertEquals(0, Util.countMatches(sql, "JOIN PAINTING "), "PAINTING back-join should be redirected to the root, but got SQL: " + sql);
    }

    /**
     * With an OUTER parent hop, the INNER back-join would filter rows with no
     * matching PAINTING_INFO. The redirect skips the back-join entirely and
     * reads from the root, which keeps those rows and changes the result set.
     * The guard in {@code findInheritanceRoundTrip} must refuse the redirect
     * so the INNER filter is preserved.
     */
    @Test
    public void sharedPkRoundTripNotRedirectedWithOuterParent() throws Exception {
        // One painting with info, one without: the outer+inner chain must
        // filter the infoless painting out of the result.
        tArtist.insert(1, "a1");
        tPainting.insert(1, 1, "with-info");
        tPainting.insert(2, 1, "without-info");
        tPaintingInfo.insert(1, "with-info");

        ObjectSelect<Painting> q = ObjectSelect.query(Painting.class)
                .where(Painting.TO_PAINTING_INFO.outer()
                        .dot(PaintingInfo.PAINTING)
                        .dot(Painting.PAINTING_TITLE).eq("with-info"));

        String sql = translateToSql(q);

        assertTrue(sql.contains("JOIN PAINTING "), "PAINTING should be re-joined when parent PAINTING_INFO is LEFT OUTER, but got SQL: " + sql);

        List<Painting> results = q.select(env.context());
        assertEquals(1, results.size(), "INNER back-join filter must be preserved; only the painting with matching info should come back");
        assertEquals("with-info", results.getFirst().getPaintingTitle());
    }

    /**
     * An entity column over a to-many path keeps the root rows that have no related objects, reading
     * {@code null} for them. The FK round trip back to ARTIST must not be redirected to the root alias,
     * which would read the artist without paintings as its own related artist.
     */
    @Test
    public void fkRoundTripInAnEntityColumnReadsNullWithoutRelatedRows() throws Exception {
        tArtist.insert(1, "a1");
        tArtist.insert(2, "a2");
        tPainting.insert(1, 1, "p1");

        List<Object[]> rows = ObjectSelect
                .columnQuery(Artist.class, Artist.ARTIST_NAME, Artist.PAINTING_ARRAY.dot(Painting.TO_ARTIST))
                .select(env.context());

        assertEquals(2, rows.size());
        Artist related = assertInstanceOf(Artist.class, rowFor(rows, "a1")[1]);
        assertEquals("a1", related.getArtistName());
        assertNull(rowFor(rows, "a2")[1], "An artist without paintings has no related artist");
    }

    /**
     * An entity column over a to-one path keeps the root rows whose related row is missing, reading
     * {@code null} for them. The shared-PK round trip back to PAINTING must not be redirected to the root
     * alias, which would read the painting without info as its own related painting.
     */
    @Test
    public void sharedPkRoundTripInAnEntityColumnReadsNullWithoutADependentRow() throws Exception {
        tArtist.insert(1, "a1");
        tPainting.insert(1, 1, "with-info");
        tPainting.insert(2, 1, "without-info");
        tPaintingInfo.insert(1, "with-info");

        List<Object[]> rows = ObjectSelect
                .columnQuery(Painting.class,
                        Painting.PAINTING_TITLE,
                        Painting.TO_PAINTING_INFO.dot(PaintingInfo.PAINTING))
                .select(env.context());

        assertEquals(2, rows.size());
        Painting related = assertInstanceOf(Painting.class, rowFor(rows, "with-info")[1]);
        assertEquals("with-info", related.getPaintingTitle());
        assertNull(rowFor(rows, "without-info")[1], "A painting without info has no related painting");
    }

    /**
     * With both hops outer, the painting without PAINTING_INFO reaches no PAINTING through the round trip,
     * so a qualifier on the far end cannot match it. Redirecting the back-join to the root alias would
     * match it against its own title.
     */
    @Test
    public void outerSharedPkRoundTripNotRedirectedThroughAMissingDependentRow() throws Exception {
        tArtist.insert(1, "a1");
        tPainting.insert(1, 1, "with-info");
        tPainting.insert(2, 1, "without-info");
        tPaintingInfo.insert(1, "with-info");

        List<Painting> results = ObjectSelect.query(Painting.class)
                .where(Painting.TO_PAINTING_INFO.outer()
                        .dot(PaintingInfo.PAINTING.outer())
                        .dot(Painting.PAINTING_TITLE).eq("without-info"))
                .select(env.context());

        assertTrue(results.isEmpty(), "No painting is reachable through the missing info row, but got " + results);
    }

    private static Object[] rowFor(List<Object[]> rows, String name) {
        List<Object[]> matched = rows.stream().filter(r -> name.equals(r[0])).toList();
        assertEquals(1, matched.size(), () -> "Expected a single row for " + name);
        return matched.getFirst();
    }

    private String translateToSql(Select<?> query) {
        DbAdapter adapter = env.dataNode().getAdapter();
        EntityResolver resolver = env.dataNode().getEntityResolver();
        return new DbAdapterDelegatedSelectTranslator().translate(query, adapter, resolver).sql();
    }
}
