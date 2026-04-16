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

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.access.DataNode;
import org.apache.cayenne.dba.DbAdapter;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.DBHelper;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.testdo.testmap.PaintingInfo;
import org.apache.cayenne.unit.di.runtime.CayenneProjects;
import org.apache.cayenne.unit.di.runtime.RuntimeCase;
import org.apache.cayenne.unit.di.runtime.UseCayenneRuntime;
import org.apache.cayenne.util.Util;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the round-trip join redirect in {@code JoinRedirectProcessor}
 * for non-inheritance scenarios: FK round-trips (A -> B via FK -> A via
 * reverse FK) and shared-PK round-trips (A -> B shared PK -> A).
 *
 * @since 5.0
 */
@UseCayenneRuntime(CayenneProjects.TESTMAP_PROJECT)
public class RoundTripRedirectIT extends RuntimeCase {

    @Inject
    private DataNode dataNode;

    @Inject
    private ObjectContext context;

    @Inject
    private DBHelper dbHelper;

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

    /**
     * With an OUTER parent hop, the INNER FK back-join would filter paintings
     * with no matching artist. The redirect would skip that filter, so it
     * must be refused.
     */
    @Test
    public void testFkRoundTripNotRedirectedWithOuterJoin() {
        ObjectSelect<Artist> q = ObjectSelect.query(Artist.class)
                .where(Artist.PAINTING_ARRAY.outer().dot(Painting.TO_ARTIST)
                        .dot(Artist.ARTIST_NAME).eq("test"));

        String sql = translateToSql(q);

        assertTrue("ARTIST should be joined when parent is LEFT OUTER, but got SQL: " + sql,
                sql.contains("JOIN ARTIST "));
    }

    /**
     * All-INNER shared-PK round-trip (PAINTING -> PAINTING_INFO -> PAINTING)
     * collapses to the root alias -- pins the baseline for the outer-parent
     * carve-out below.
     */
    @Test
    public void testInnerSharedPkRoundTripRedirected() {
        ObjectSelect<Painting> q = ObjectSelect.query(Painting.class)
                .where(Painting.TO_PAINTING_INFO
                        .dot(PaintingInfo.PAINTING)
                        .dot(Painting.PAINTING_TITLE).eq("test"));

        String sql = translateToSql(q);

        // Root PAINTING is in FROM; the back-hop should be redirected to it,
        // leaving no "JOIN PAINTING " in the output.
        assertEquals("PAINTING back-join should be redirected to the root, but got SQL: " + sql,
                0, Util.countMatches(sql, "JOIN PAINTING "));
    }

    /**
     * With an OUTER parent hop, the INNER back-join would filter rows with no
     * matching PAINTING_INFO. The redirect skips the back-join entirely and
     * reads from the root, which keeps those rows and changes the result set.
     * The guard in {@code findInheritanceRoundTrip} must refuse the redirect
     * so the INNER filter is preserved.
     */
    @Test
    public void testSharedPkRoundTripNotRedirectedWithOuterParent() throws Exception {
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

        assertTrue("PAINTING should be re-joined when parent PAINTING_INFO is LEFT OUTER, but got SQL: " + sql,
                sql.contains("JOIN PAINTING "));

        List<Painting> results = q.select(context);
        assertEquals("INNER back-join filter must be preserved; only the painting with matching info should come back",
                1, results.size());
        assertEquals("with-info", results.get(0).getPaintingTitle());
    }

    private String translateToSql(ObjectSelect<?> query) {
        DbAdapter adapter = dataNode.getAdapter();
        EntityResolver resolver = dataNode.getEntityResolver();
        return new DefaultSelectTranslator(query, adapter, resolver).getSql();
    }
}
