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
import org.apache.cayenne.testdo.relationships_shared_pk_fk.Member;
import org.apache.cayenne.testdo.relationships_shared_pk_fk.MemberProfile;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Exercises the join redirect on a dependent table that shares its master's primary key and also references a master
 * row by a plain FK. An FK round trip (member -> mentee profiles -> mentor) returns to the root member, but the
 * referencing profile it passes through belongs to the mentee, so a shared-PK hop after it must reach the mentor's own
 * profile.
 * <p>
 * Data: members m1, m2, m3 with profiles x, y and z. Profile y (of m2) names m1 as its mentor.
 */
public class SharedPkHopAfterFkRoundTripIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.RELATIONSHIPS_SHARED_PK_FK_PROJECT);

    @BeforeEach
    public void createMentorship() throws Exception {
        env.table("MEMBER", "ID", "NAME").insert(1, "m1").insert(2, "m2").insert(3, "m3");
        env.table("MEMBER_PROFILE", "MEMBER_ID", "BIO").insert(1, "x").insert(3, "z");
        env.table("MEMBER_PROFILE", "MEMBER_ID", "BIO", "MENTOR_ID").insert(2, "y", 1);
    }

    @Test
    public void sharedPkHopAfterFkRoundTripReadsTheMentorsProfile() {
        assertEquals(List.of("m1"), memberNames(Member.MENTEE_PROFILES
                .dot(MemberProfile.MENTOR)
                .dot(Member.PROFILE)
                .dot(MemberProfile.BIO).eq("x")));
        assertEquals(List.of(), memberNames(Member.MENTEE_PROFILES
                .dot(MemberProfile.MENTOR)
                .dot(Member.PROFILE)
                .dot(MemberProfile.BIO).eq("y")));
    }

    /**
     * The mentor hop is still redirected to the root member, and the profile hop is joined from it.
     */
    @Test
    public void sharedPkHopAfterFkRoundTripIsJoined() {
        String sql = translateToSql(ObjectSelect.query(Member.class)
                .where(Member.MENTEE_PROFILES
                        .dot(MemberProfile.MENTOR)
                        .dot(Member.PROFILE)
                        .dot(MemberProfile.BIO).eq("x")));

        assertEquals(0, countJoins(sql, "MEMBER"), "The mentor hop should be redirected, but got SQL: " + sql);
        assertEquals(2, countJoins(sql, "MEMBER_PROFILE"), "The mentor's profile should be joined, but got: " + sql);
    }

    @Test
    public void outerSharedPkHopAfterFkRoundTripReadsTheMentorsProfile() {
        assertEquals(List.of("m1"), memberNames(Member.MENTEE_PROFILES
                .dot(MemberProfile.MENTOR)
                .dot(Member.PROFILE.outer())
                .dot(MemberProfile.BIO).eq("x")));
    }

    @Test
    public void sharedPkHopAfterOuterFkRoundTripReadsTheMentorsProfile() {
        assertEquals(List.of("m1"), memberNames(Member.MENTEE_PROFILES
                .dot(MemberProfile.MENTOR.outer())
                .dot(Member.PROFILE)
                .dot(MemberProfile.BIO).eq("x")));
    }

    /**
     * An outer first hop allows no FK round trip redirect, so every hop is joined.
     */
    @Test
    public void allOuterPathJoinsEveryHop() {
        Expression qualifier = Member.MENTEE_PROFILES.outer()
                .dot(MemberProfile.MENTOR.outer())
                .dot(Member.PROFILE.outer())
                .dot(MemberProfile.BIO).eq("x");

        assertEquals(List.of("m1"), memberNames(qualifier));

        String sql = translateToSql(ObjectSelect.query(Member.class).where(qualifier));
        assertEquals(1, countJoins(sql, "MEMBER"), "The mentor hop should be joined, but got SQL: " + sql);
        assertEquals(2, countJoins(sql, "MEMBER_PROFILE"), "Both profile hops should be joined, but got SQL: " + sql);
    }

    /**
     * Without a shared-PK hop after it, the FK round trip still collapses to the root.
     */
    @Test
    public void fkRoundTripWithoutASharedPkHopIsRedirected() {
        Expression qualifier = Member.MENTEE_PROFILES.dot(MemberProfile.MENTOR).dot(Member.NAME).eq("m1");

        assertEquals(List.of("m1"), memberNames(qualifier));

        String sql = translateToSql(ObjectSelect.query(Member.class).where(qualifier));
        assertEquals(0, countJoins(sql, "MEMBER"), "The mentor hop should be redirected, but got SQL: " + sql);
    }

    @Test
    public void sharedPkHopAfterFkRoundTripFromADependentRoot() {
        assertEquals(List.of("y"), profileBios(MemberProfile.MENTOR
                .dot(Member.MENTEE_PROFILES)
                .dot(MemberProfile.MENTOR)
                .dot(Member.PROFILE)
                .dot(MemberProfile.BIO).eq("x")));
        assertEquals(List.of(), profileBios(MemberProfile.MENTOR
                .dot(Member.MENTEE_PROFILES)
                .dot(MemberProfile.MENTOR)
                .dot(Member.PROFILE)
                .dot(MemberProfile.BIO).eq("y")));
    }

    @Test
    public void sharedPkHopAfterFkRoundTripInAColumn() {
        List<Object[]> rows = ObjectSelect.columnQuery(Member.class,
                        Member.NAME,
                        Member.MENTEE_PROFILES.dot(MemberProfile.MENTOR).dot(Member.PROFILE).dot(MemberProfile.BIO))
                .select(env.context());

        assertEquals(1, rows.size());
        assertEquals("m1", rows.getFirst()[0]);
        assertEquals("x", rows.getFirst()[1], "The column should read the mentor's own profile");
    }

    /**
     * The DISJOINT prefetch query of the mentor qualifies mentors by the reverse path, which runs the FK round trip
     * and then the shared-PK hop to the mentor's profile.
     */
    @Test
    public void disjointPrefetchOverSharedPkHopAfterFkRoundTrip() {
        List<MemberProfile> profiles = ObjectSelect.query(MemberProfile.class)
                .where(MemberProfile.MENTOR.dot(Member.PROFILE).dot(MemberProfile.BIO).eq("x"))
                .prefetch(MemberProfile.MENTOR.disjoint())
                .select(env.context());

        assertEquals(1, profiles.size());
        assertEquals("y", profiles.getFirst().getBio());
        Member mentor = assertInstanceOf(Member.class, profiles.getFirst().readPropertyDirectly("mentor"),
                "The prefetch should resolve the mentor");
        assertEquals("m1", mentor.getName());
    }

    private static List<String> memberNames(Expression qualifier) {
        return ObjectSelect.columnQuery(Member.class, Member.NAME)
                .where(qualifier)
                .orderBy(Member.NAME.asc())
                .select(env.context());
    }

    private static List<String> profileBios(Expression qualifier) {
        return ObjectSelect.columnQuery(MemberProfile.class, MemberProfile.BIO)
                .where(qualifier)
                .orderBy(MemberProfile.BIO.asc())
                .select(env.context());
    }

    private String translateToSql(Select<?> query) {
        DbAdapter adapter = env.dataNode().getAdapter();
        EntityResolver resolver = env.dataNode().getEntityResolver();
        return new DbAdapterDelegatedSelectTranslator().translate(query, adapter, resolver).sql();
    }

    /**
     * Counts {@code JOIN <tableName>} occurrences. The trailing space keeps MEMBER from matching MEMBER_PROFILE.
     */
    private static int countJoins(String sql, String tableName) {
        return Util.countMatches(sql, "JOIN " + tableName + " ");
    }
}
