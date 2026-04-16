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
import org.apache.cayenne.exp.ExpressionFactory;
import org.apache.cayenne.exp.property.NumericProperty;
import org.apache.cayenne.exp.property.PropertyFactory;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.query.Select;
import org.apache.cayenne.testdo.things.Thing;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.apache.cayenne.util.Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THING and BALL reference each other by to-one relationships over (VOLUME, WEIGHT), which no key makes unique. An FK
 * round trip THING -> BALL -> THING may then reach other THING rows with the same volume and weight, so it can't be
 * redirected to the THING it starts from.
 */
public class FkRoundTripOverNonKeyColumnsIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.THINGS_PROJECT);

    private static final NumericProperty<Long> THING_ID = PropertyFactory.createNumeric(
            ExpressionFactory.dbPathExp("ID"), Long.class);

    @Test
    public void roundTripOverNonKeyColumnsReachesEveryMatchingRow() throws Exception {
        env.table("BAG", "ID").insert(1);
        env.table("BOX", "ID", "BAG_ID").insert(1, 1);
        env.table("BALL", "ID", "BOX_ID", "THING_VOLUME", "THING_WEIGHT").insert(1, 1, 10, 1);
        env.table("THING", "ID", "VOLUME", "WEIGHT").insert(1, 10, 1).insert(2, 10, 1).insert(3, 20, 2);

        Expression qualifier = ExpressionFactory.exp("db:BALL.THING.ID = 2");

        List<Long> ids = ObjectSelect.columnQuery(Thing.class, THING_ID)
                .where(qualifier)
                .orderBy(THING_ID.asc())
                .select(env.context());
        assertEquals(List.of(1L, 2L), ids);

        String sql = translateToSql(ObjectSelect.query(Thing.class).where(qualifier));
        assertEquals(1, Util.countMatches(sql, "JOIN THING "), "THING should be joined again, but got SQL: " + sql);
    }

    private String translateToSql(Select<?> query) {
        DbAdapter adapter = env.dataNode().getAdapter();
        EntityResolver resolver = env.dataNode().getEntityResolver();
        return new DbAdapterDelegatedSelectTranslator().translate(query, adapter, resolver).sql();
    }
}
