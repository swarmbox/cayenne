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
import org.apache.cayenne.exp.Expression;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.Select;
import org.apache.cayenne.testdo.relationships_shared_pk_fk.KeyPair;
import org.apache.cayenne.testdo.relationships_shared_pk_fk.Member;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the join redirect over a compound primary key joined to itself with its columns swapped. The swapped join
 * joins all primary key columns on both sides, yet it leads to a different row, so it must never be redirected to the
 * alias it starts from.
 * <p>
 * Data: key pairs (1, 2) "k-12", (2, 1) "k-21", (1, 1) "k-11" and (3, 4) "k-34", which has no swapped partner. Members
 * m1, m2 and m3 reference pairs (2, 1), (3, 4) and (1, 1); m4 references none.
 */
public class PermutedCompoundKeyRedirectIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_SHARED_PK_FK_PROJECT);

    @BeforeEach
    public void createKeyPairs() throws Exception {
        env.table("KEY_PAIR", "FIRST_KEY", "SECOND_KEY", "NAME")
                .insert(1, 2, "k-12")
                .insert(2, 1, "k-21")
                .insert(1, 1, "k-11")
                .insert(3, 4, "k-34");
        env.table("MEMBER", "ID", "NAME", "PAIR_FIRST_KEY", "PAIR_SECOND_KEY")
                .insert(1, "m1", 2, 1)
                .insert(2, "m2", 3, 4)
                .insert(3, "m3", 1, 1);
        env.table("MEMBER", "ID", "NAME").insert(4, "m4");
    }

    @Test
    public void swappedKeyFromTheRootReadsThePartner() {
        Expression qualifier = KeyPair.SWAPPED.dot(KeyPair.NAME).eq("k-12");

        assertEquals(List.of("k-21"), pairNames(qualifier));

        String sql = translateToSql(ObjectSelect.query(KeyPair.class).where(qualifier));
        assertEquals(1, Util.countMatches(sql, "JOIN KEY_PAIR "), "The swapped pair should be joined, but got: " + sql);
    }

    @Test
    public void swappedKeyFromAJoinedPairReadsThePartner() {
        assertEquals(List.of("m1"), memberNames(Member.KEY_PAIR.dot(KeyPair.SWAPPED).dot(KeyPair.NAME).eq("k-12")));
    }

    @Test
    public void outerSwappedKeyFindsPairsWithoutAPartner() {
        assertEquals(List.of("m2", "m4"), memberNames(Member.KEY_PAIR.outer()
                .dot(KeyPair.SWAPPED.outer())
                .dot(KeyPair.NAME).isNull()));
    }

    @Test
    public void jointPrefetchOfTheSwappedKeyResolvesThePartner() {
        List<KeyPair> pairs = ObjectSelect.query(KeyPair.class)
                .prefetch(KeyPair.SWAPPED.joint())
                .orderBy(KeyPair.NAME.asc())
                .select(env.context());

        Map<String, String> partners = new LinkedHashMap<>();
        for (KeyPair pair : pairs) {
            KeyPair partner = (KeyPair) pair.readPropertyDirectly("swapped");
            partners.put(pair.getName(), partner != null ? partner.getName() : null);
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("k-11", "k-11");
        expected.put("k-12", "k-21");
        expected.put("k-21", "k-12");
        expected.put("k-34", null);
        assertEquals(expected, partners);
    }

    private static List<String> pairNames(Expression qualifier) {
        return ObjectSelect.columnQuery(KeyPair.class, KeyPair.NAME)
                .where(qualifier)
                .orderBy(KeyPair.NAME.asc())
                .select(env.context());
    }

    private static List<String> memberNames(Expression qualifier) {
        return ObjectSelect.columnQuery(Member.class, Member.NAME)
                .where(qualifier)
                .orderBy(Member.NAME.asc())
                .select(env.context());
    }

    private String translateToSql(Select<?> query) {
        DbAdapter adapter = env.dataNode().getAdapter();
        EntityResolver resolver = env.dataNode().getEntityResolver();
        return new DbAdapterDelegatedSelectTranslator().translate(query, adapter, resolver).sql();
    }
}
