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

import org.apache.cayenne.exp.ExpressionFactory;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.testmap.ArtGroup;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DisjointPrefetchDbIdQualifierIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT);

    protected TableHelper artistHelper;
    protected TableHelper artgroupHelper;
    protected TableHelper artistGroupHelper;

    @BeforeEach
    public void cleanUpDB() throws Exception {
        env.table("ARTGROUP").update().set("PARENT_GROUP_ID", null, Types.INTEGER).execute();
        env.dbCleaner().clean();

        artistHelper = env.table("ARTIST", "ARTIST_ID", "ARTIST_NAME");
        artgroupHelper = env.table("ARTGROUP", "GROUP_ID", "NAME", "PARENT_GROUP_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);
        artistGroupHelper = env.table("ARTIST_GROUP", "GROUP_ID", "ARTIST_ID");
    }

    private void createGroupsDataSet() throws Exception {
        artistHelper.insert(11, "artist2");
        artistHelper.insert(101, "artist3");

        artgroupHelper.insert(1, "g1", null);
        artgroupHelper.insert(2, "g2", 1);
        artgroupHelper.insert(3, "g3", 1);
        artgroupHelper.insert(4, "g4", null);
        artgroupHelper.insert(5, "g5", 4);

        artistGroupHelper.insert(2, 11);
        artistGroupHelper.insert(3, 11);
        artistGroupHelper.insert(5, 101);
    }

    @Test
    public void toManyUnderDbIdQualifier() throws Exception {
        createGroupsDataSet();

        ArtGroup g1 = ObjectSelect.query(ArtGroup.class)
                .where(ExpressionFactory.matchDbIdExp(ArtGroup.GROUP_ID_PK_COLUMN, 1))
                .prefetch(ArtGroup.CHILD_GROUPS_ARRAY.disjoint())
                .selectOne(env.context());

        env.runWithQueriesBlocked(() -> assertEquals(List.of("g2", "g3"),
                g1.getChildGroupsArray().stream().map(ArtGroup::getName).sorted().toList()));
    }

    @Test
    public void flattenedUnderDbIdQualifier() throws Exception {
        createGroupsDataSet();

        Artist artist2 = ObjectSelect.query(Artist.class)
                .where(ExpressionFactory.matchDbIdExp(Artist.ARTIST_ID_PK_COLUMN, 11))
                .prefetch(Artist.GROUP_ARRAY.disjoint())
                .selectOne(env.context());

        env.runWithQueriesBlocked(() -> assertEquals(List.of("g2", "g3"),
                artist2.getGroupArray().stream().map(ArtGroup::getName).sorted().toList()));
    }

    @Test
    public void prefixedDbIdQualifier() throws Exception {
        createGroupsDataSet();

        List<ArtGroup> roots = ObjectSelect.query(ArtGroup.class)
                .where(ExpressionFactory.matchDbIdExp("toParentGroup.GROUP_ID", 1))
                .prefetch(ArtGroup.ARTIST_ARRAY.disjoint())
                .orderBy(ArtGroup.NAME.asc())
                .select(env.context());

        env.runWithQueriesBlocked(() -> {
            assertEquals(List.of("g2", "g3"), roots.stream().map(ArtGroup::getName).toList());
            for (ArtGroup root : roots) {
                assertEquals(List.of("artist2"),
                        root.getArtistArray().stream().map(Artist::getArtistName).toList());
            }
        });
    }

    @Test
    public void idMapQualifier() throws Exception {
        createGroupsDataSet();

        ArtGroup g1 = ObjectSelect.query(ArtGroup.class)
                .where(ArtGroup.SELF.eqIdMap(Map.of(ArtGroup.GROUP_ID_PK_COLUMN, 1)))
                .prefetch(ArtGroup.CHILD_GROUPS_ARRAY.disjoint())
                .selectOne(env.context());

        env.runWithQueriesBlocked(() -> assertEquals(List.of("g2", "g3"),
                g1.getChildGroupsArray().stream().map(ArtGroup::getName).sorted().toList()));
    }

    @Test
    public void existsUnderDbIdPath() throws Exception {
        createGroupsDataSet();

        List<ArtGroup> roots = ObjectSelect.query(ArtGroup.class)
                .where(ExpressionFactory.exists(ExpressionFactory.matchDbIdExp("childGroupsArray.GROUP_ID", 2)))
                .select(env.context());

        assertEquals(List.of("g1"), roots.stream().map(ArtGroup::getName).toList());
    }
}
