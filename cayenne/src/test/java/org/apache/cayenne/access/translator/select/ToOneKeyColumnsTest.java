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
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the key columns {@link ToOneKeyColumns} names for flattened to-ones against the columns the SELECT
 * translation of their paths fetches, on path shapes the test projects do not map: more than two hops ending with an
 * FK, a two-column FK and an inverse one-to-one as the last hop.
 */
public class ToOneKeyColumnsTest {

    private EntityResolver resolver;
    private ObjEntity src;

    @BeforeEach
    public void buildModel() {
        DataMap map = new DataMap("M");

        // SRC has a vertical-inheritance style sub-table SUB, which holds the FKs
        DbEntity srcTable = addDbEntity(map, "SRC", "ID");
        srcTable.addAttribute(new DbAttribute("X_ID"));
        DbEntity subTable = addDbEntity(map, "SUB", "ID");
        subTable.addAttribute(new DbAttribute("X_ID"));
        subTable.addAttribute(new DbAttribute("Y_ID1"));
        subTable.addAttribute(new DbAttribute("Y_ID2"));
        DbEntity xTable = addDbEntity(map, "X", "ID");
        xTable.addAttribute(new DbAttribute("Z_ID"));
        addDbEntity(map, "Z", "ID");
        addDbEntity(map, "Y", "K1", "K2");
        DbEntity wTable = addDbEntity(map, "W", "ID");
        wTable.addAttribute(new DbAttribute("SUB_ID"));

        addDbRelationship(srcTable, "sub", "SUB", "ID", "ID").setToDependentPK(true);
        addDbRelationship(srcTable, "x", "X", "X_ID", "ID");
        addDbRelationship(subTable, "x", "X", "X_ID", "ID");
        addDbRelationship(xTable, "z", "Z", "Z_ID", "ID");
        DbRelationship subToY = addDbRelationship(subTable, "y", "Y", "Y_ID1", "K1");
        subToY.addJoin(new DbJoin(subToY, "Y_ID2", "K2"));
        addDbRelationship(subTable, "w", "W", "ID", "SUB_ID");

        src = addObjEntity(map, "Src", "SRC");
        addObjEntity(map, "X", "X");
        addObjEntity(map, "Z", "Z");
        addObjEntity(map, "Y", "Y");
        addObjEntity(map, "W", "W");

        addObjRelationship(src, "x", "X", "x");
        addObjRelationship(src, "subX", "X", "sub.x");
        addObjRelationship(src, "subXZ", "Z", "sub.x.z");
        addObjRelationship(src, "xZ", "Z", "x.z");
        addObjRelationship(src, "subY", "Y", "sub.y");
        addObjRelationship(src, "subW", "W", "sub.w");

        resolver = new EntityResolver(List.of(map));
    }

    @Test
    public void notFlattened() {
        ObjRelationship x = src.getRelationship("x");
        assertTrue(ToOneKeyColumns.inSourceRow(x));
        assertEquals(List.of(), ToOneKeyColumns.flattenedKeyPaths(x));
    }

    @Test
    public void lastHopFk() {
        assertKey("subX", List.of("sub.X_ID"), List.of("sub.ID", "sub.X_ID"));
    }

    @Test
    public void threeHopsLastHopFk() {
        assertKey("subXZ", List.of("sub.x.Z_ID"), List.of("sub.ID", "sub.x.ID", "sub.x.Z_ID"));
    }

    @Test
    public void firstHopFk() {
        assertKey("xZ", List.of("x.Z_ID"), List.of("x.ID", "x.Z_ID"));
    }

    @Test
    public void lastHopTwoColumnFk() {
        assertKey("subY", List.of("sub.Y_ID1", "sub.Y_ID2"), List.of("sub.ID", "sub.Y_ID1", "sub.Y_ID2"));
    }

    @Test
    public void lastHopInverseOneToOne() {
        assertKey("subW", List.of("sub.w.ID"), List.of("sub.ID", "sub.w.ID"));
    }

    @Test
    public void noKeyWhenFirstHopIsNotToPk() {
        // SRC -> W over W.SUB_ID, a non-PK column, then W -> SUB over that FK
        addDbRelationship(src.getDbEntity(), "toW", "W", "ID", "SUB_ID");
        addDbRelationship(src.getDataMap().getDbEntity("W"), "sub", "SUB", "SUB_ID", "ID");
        addObjEntity(src.getDataMap(), "Sub", "SUB");
        addObjRelationship(src, "wSub", "Sub", "toW.sub");

        ObjRelationship wSub = src.getRelationship("wSub");
        assertFalse(ToOneKeyColumns.inSourceRow(wSub));
        assertEquals(List.of(), ToOneKeyColumns.flattenedKeyPaths(wSub));
    }

    /**
     * @param key     the key the helper must name
     * @param fetched all the columns the SELECT fetches for the relationship: the key, and the PKs of the tables on
     *                the path, which a joint prefetch reads as the PKs of the descriptor's additional DB entities
     */
    private void assertKey(String relationship, List<String> key, List<String> fetched) {
        ObjRelationship toOne = src.getRelationship(relationship);
        assertTrue(ToOneKeyColumns.inSourceRow(toOne));
        assertEquals(key, ToOneKeyColumns.flattenedKeyPaths(toOne).stream().map(CayennePath::value).toList());
        assertEquals(fetched, translatedColumns(toOne));
    }

    /**
     * Returns the columns fetched for a to-one in a row of its source entity, translated as DescriptorColumnExtractor
     * translates them, in the order of the translation, each column once.
     */
    private List<String> translatedColumns(ObjRelationship toOne) {
        SelectTranslatorContext context = new SelectTranslatorContext(
                ObjectSelect.query(Object.class), Mockito.mock(DbAdapter.class), resolver, null);
        PathTranslationResult result = context.getPathTranslator()
                .translatePath(toOne.getSourceEntity(), CayennePath.of(toOne.getName()), null);

        List<String> columns = new ArrayList<>();
        for (int i = 0; i < result.getDbAttributes().size(); i++) {
            String column = result.getAttributePaths().get(i).dot(result.getDbAttributes().get(i).getName()).value();
            if (!columns.contains(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static DbEntity addDbEntity(DataMap map, String name, String... pkNames) {
        DbEntity entity = new DbEntity(name);
        for (String pkName : pkNames) {
            DbAttribute pk = new DbAttribute(pkName);
            pk.setPrimaryKey(true);
            entity.addAttribute(pk);
        }
        map.addDbEntity(entity);
        return entity;
    }

    private static DbRelationship addDbRelationship(DbEntity source, String name, String target, String from,
                                                    String to) {
        DbRelationship relationship = new DbRelationship(name);
        source.addRelationship(relationship);
        relationship.setTargetEntityName(target);
        relationship.addJoin(new DbJoin(relationship, from, to));
        return relationship;
    }

    private static ObjEntity addObjEntity(DataMap map, String name, String dbEntityName) {
        ObjEntity entity = new ObjEntity(name);
        entity.setDbEntityName(dbEntityName);
        map.addObjEntity(entity);
        return entity;
    }

    private static void addObjRelationship(ObjEntity source, String name, String target, String dbPath) {
        ObjRelationship relationship = new ObjRelationship(name);
        source.addRelationship(relationship);
        relationship.setTargetEntityName(target);
        relationship.setDbRelationshipPath(dbPath);
    }
}
