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

package org.apache.cayenne.query;

import org.apache.cayenne.access.DataNode;
import org.apache.cayenne.access.translator.select.DefaultSelectTranslator;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.unit.di.runtime.CayenneProjects;
import org.apache.cayenne.unit.di.runtime.RuntimeCase;
import org.apache.cayenne.unit.di.runtime.UseCayenneRuntime;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Verifies that the translator eliminates the FK round-trip join that the
 * disjoint-prefetch router inadvertently introduces when the original
 * query's qualifier traverses the prefetched relationship.
 * <p>
 * This occurs when the disjoint prefetch generates a secondary query rooted at
 * the prefetch target. The main qualifier was written relative to the original
 * root, so to re-apply it Cayenne prepends the reverse of the prefetched
 * relationship to navigate back. The prepended reverse plus the qualifier's
 * original traversal is an unnecessary round-trip: target -> reverse -> target.
 *
 * @since 5.0
 */
@UseCayenneRuntime(CayenneProjects.TESTMAP_PROJECT)
public class FluentSelectPrefetchFkRoundTripIT extends RuntimeCase {

    @Inject
    private DataNode dataNode;

    /**
     * Test that the translator removes the unnecessary round-trip JOIN in the
     * prefetch's query (ARTIST -> PAINTING -> ARTIST).
     */
    @Test
    public void testDisjointPrefetchFkRoundTripRedirected() {
        ObjectSelect<Painting> query = ObjectSelect.query(Painting.class)
                .where(Painting.TO_ARTIST.dot(Artist.ARTIST_NAME).eq("abc"))
                .prefetch(Painting.TO_ARTIST.disjoint());

        PrefetchSelectQuery<?> prefetch = routePrefetch(query);
        assertEquals(dataNode.getEntityResolver().getObjEntity(Artist.class).getName(),
                prefetch.getEntityName());

        String sql = new DefaultSelectTranslator(prefetch,
                dataNode.getAdapter(), dataNode.getEntityResolver()).getSql();

        // ARTIST is the prefetch root (t0). The redirect must prevent
        // the additional JOIN back to ARTIST. The trailing space avoids
        // matching a hypothetical ARTIST_<suffix> table.
        assertFalse("Prefetch query must not re-join ARTIST via the FK round-trip, but got SQL: " + sql,
                sql.contains("JOIN ARTIST "));
    }

    private PrefetchSelectQuery<?> routePrefetch(ObjectSelect<?> query) {
        FluentSelectPrefetchRouterAction action = new FluentSelectPrefetchRouterAction();
        MockQueryRouter router = new MockQueryRouter();
        action.route(query, router, dataNode.getEntityResolver());
        return (PrefetchSelectQuery<?>) router.getQueries().get(0);
    }
}
