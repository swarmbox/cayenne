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
import org.apache.cayenne.DataChannelQueryFilter;
import org.apache.cayenne.DataChannelQueryFilterChain;
import org.apache.cayenne.DataRow;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.QueryResult;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.Query;
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

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Resolving a to-one fault from the database gives a target that isn't registered in the context the row just read,
 * not an older one from the shared cache, and puts that row in the cache. The query that resolves it is an
 * ObjectSelect to the query filters.
 */
public class ToOneFaultFreshTargetIT {

    private static final QueryRecorder QUERIES = new QueryRecorder();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT)
            .withExtraModules(binder -> CoreModule.extend(binder).addQueryFilter(QUERIES));

    private TableHelper tArtist;
    private TableHelper tPaintingInfo;

    @BeforeEach
    public void createTestData() throws SQLException {
        tArtist = env.table("ARTIST", "ARTIST_ID", "ARTIST_NAME");
        TableHelper tPainting = env.table("PAINTING").setColumns("PAINTING_ID", "ARTIST_ID", "PAINTING_TITLE")
                .setColumnTypes(Types.INTEGER, Types.BIGINT, Types.VARCHAR);
        tPaintingInfo = env.table("PAINTING_INFO", "PAINTING_ID", "TEXT_REVIEW");

        tArtist.insert(1, "old");
        tPainting.insert(1, 1, "p1");
        tPaintingInfo.insert(1, "old");
    }

    @Test
    public void dependentPkTargetIsReadFromTheDatabase() throws SQLException {
        SelectById.queryId(PaintingInfo.class, 1).selectOne(newContext());
        tPaintingInfo.update().set("TEXT_REVIEW", "new").where("PAINTING_ID", 1).execute();

        Painting painting = SelectById.queryId(Painting.class, 1).selectOne(newContext());
        QUERIES.seen.clear();
        PaintingInfo info = painting.getToPaintingInfo();

        assertAll(
                () -> assertEquals("new", info.getTextReview()),
                () -> assertEquals("new", cachedRow(info.getObjectId()).get("TEXT_REVIEW")),
                () -> assertEquals(1, QUERIES.seen.size(), () -> "Queries: " + QUERIES.seen),
                () -> assertInstanceOf(ObjectSelect.class, QUERIES.seen.getFirst()));
    }

    @Test
    public void foreignKeyTargetIsReadFromTheDatabaseWhenTheSourceRowIsNotCached() throws SQLException {
        SelectById.queryId(Artist.class, 1).selectOne(newContext());
        tArtist.update().set("ARTIST_NAME", "new").where("ARTIST_ID", 1).execute();

        Painting painting = SelectById.queryId(Painting.class, 1).selectOne(newContext());
        env.runtime().getDataDomain().getSharedSnapshotCache().forgetSnapshot(painting.getObjectId());
        Artist artist = painting.getToArtist();

        assertAll(
                () -> assertEquals("new", artist.getArtistName()),
                () -> assertEquals("new", cachedRow(artist.getObjectId()).get("ARTIST_NAME")));
    }

    private static ObjectContext newContext() {
        return env.runtime().newContext();
    }

    private static DataRow cachedRow(ObjectId id) {
        return env.runtime().getDataDomain().getSharedSnapshotCache().getCachedSnapshot(id);
    }

    private static class QueryRecorder implements DataChannelQueryFilter {

        final List<Query> seen = new CopyOnWriteArrayList<>();

        @Override
        public List<QueryResult> onQuery(ObjectContext originatingContext, Query query, boolean iteratedResult,
                                     DataChannelQueryFilterChain filterChain) {
            seen.add(query);
            return filterChain.onQuery(originatingContext, query, iteratedResult);
        }
    }
}
