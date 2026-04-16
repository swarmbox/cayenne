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
import org.apache.cayenne.exp.ExpressionFactory;
import org.apache.cayenne.exp.property.BaseProperty;
import org.apache.cayenne.exp.property.NumericProperty;
import org.apache.cayenne.exp.property.PropertyFactory;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.map.ObjAttribute;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.query.ColumnSelect;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MEMBER_NOTE shares MEMBER's primary key without vertical inheritance and has no FK constraint, so a note may exist
 * without its member. A round trip from a note to its member and back must not read such a note as reachable.
 * <p>
 * Data: member m1 with note n-1, and note n-10 whose member 10 doesn't exist.
 */
public class DependentRowWithoutMasterIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_SHARED_PK_FK_PROJECT);

    private static final NumericProperty<Integer> MEMBER_ID = PropertyFactory.createNumeric(
            ExpressionFactory.dbPathExp("MEMBER_ID"), Integer.class);

    @BeforeEach
    public void createNotes() throws Exception {
        env.table("MEMBER", "ID", "NAME").insert(1, "m1");
        env.table("MEMBER_NOTE", "MEMBER_ID", "TEXT").insert(1, "n-1").insert(10, "n-10");
    }

    /**
     * The inner join to the member drops the note without a member.
     */
    @Test
    public void innerRoundTripThroughTheMasterKeepsTheMasterJoin() {
        ColumnSelect<Object[]> query = notesWith("member.note.TEXT");

        assertEquals(List.of(List.of(1, "n-1")), rows(query));

        String sql = translateToSql(query);
        assertEquals(1, Util.countMatches(sql, "JOIN MEMBER "), "MEMBER should be joined, but got SQL: " + sql);
        assertEquals(0, Util.countMatches(sql, "JOIN MEMBER_NOTE "), "The note hop should be redirected: " + sql);
    }

    /**
     * The note without a member reaches no note through the outer round trip.
     */
    @Test
    public void outerRoundTripThroughTheMasterReadsNothingWithoutIt() {
        ColumnSelect<Object[]> query = notesWith("member+.note+.TEXT");

        assertEquals(Arrays.asList(List.of(1, "n-1"), Arrays.asList(10, null)), rows(query));

        String sql = translateToSql(query);
        assertEquals(1, Util.countMatches(sql, "JOIN MEMBER "), "MEMBER should be joined, but got SQL: " + sql);
        assertEquals(1, Util.countMatches(sql, "JOIN MEMBER_NOTE "), "The note should be joined, but got: " + sql);
    }

    /**
     * The outer round trip asks whether its step to the member is a vertical inheritance link, which looks at the
     * attributes of every sub-entity. A sub-entity elsewhere whose db path doesn't resolve leaves the answer to the
     * others, on every run of the query.
     */
    @ParameterizedTest
    @ValueSource(strings = {"profile.NO_SUCH_COLUMN", "noSuchRelationship.BIO"})
    public void outerRoundTripIgnoresAStaleAttributeOfAnotherEntity(String stalePath) {
        EntityResolver resolver = env.runtime().getDataDomain().getEntityResolver();
        DataMap map = resolver.getObjEntity("Member").getDataMap();
        ObjEntity staleEntity = new ObjEntity("StaleMember");
        map.addObjEntity(staleEntity);
        staleEntity.setSuperEntityName("Member");
        ObjAttribute staleAttribute = new ObjAttribute("stale", String.class.getName(), staleEntity);
        staleAttribute.setDbAttributePath(stalePath);
        staleEntity.addAttribute(staleAttribute);
        resolver.refreshMappingCache();

        try {
            List<List<Object>> expected = Arrays.asList(List.of(1, "n-1"), Arrays.asList(10, null));
            assertEquals(expected, rows(notesWith("member+.note+.TEXT")));
            assertEquals(expected, rows(notesWith("member+.note+.TEXT")));
        } finally {
            map.removeObjEntity(staleEntity.getName());
            resolver.refreshMappingCache();
        }
    }

    private static ColumnSelect<Object[]> notesWith(String textPath) {
        BaseProperty<String> text = PropertyFactory.createBase(ExpressionFactory.dbPathExp(textPath), String.class);
        return ObjectSelect.dbQuery("MEMBER_NOTE").columns(MEMBER_ID, text).orderBy(MEMBER_ID.asc());
    }

    private static List<List<Object>> rows(ColumnSelect<Object[]> query) {
        return query.select(env.context()).stream().map(Arrays::asList).toList();
    }

    private String translateToSql(ColumnSelect<Object[]> query) {
        DbAdapter adapter = env.dataNode().getAdapter();
        EntityResolver resolver = env.dataNode().getEntityResolver();
        return new DbAdapterDelegatedSelectTranslator().translate(query, adapter, resolver).sql();
    }
}
