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

import org.apache.cayenne.access.flush.DataDomainFlushAction;
import org.apache.cayenne.access.flush.DataDomainFlushActionFactory;
import org.apache.cayenne.access.flush.DefaultDataDomainFlushAction;
import org.apache.cayenne.access.flush.operation.DbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOpSorter;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOpFactory;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A subclass of the default flush action in another package can override how the operations of a flush are created.
 */
public class DataDomainFlushActionOverrideIT {

    private static final AtomicInteger CREATE_CALLS = new AtomicInteger();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT)
            .withExtraModules(binder -> binder.bind(DataDomainFlushActionFactory.class)
                    .to(CountingFlushActionFactory.class));

    @Test
    public void overriddenOperationCreationIsUsed() throws Exception {
        CREATE_CALLS.set(0);

        DataContext context = env.context();
        Artist artist = context.newObject(Artist.class);
        artist.setArtistName("a1");
        context.commitChanges();

        assertEquals(1, CREATE_CALLS.get());
        assertEquals(1, env.table("ARTIST", "ARTIST_ID").getRowCount());
    }

    public static class CountingFlushActionFactory implements DataDomainFlushActionFactory {

        @Inject
        private DbRowOpSorter sorter;

        @Inject
        private DeleteDbRowOpFactory deleteDbRowOpFactory;

        @Override
        public DataDomainFlushAction createFlushAction(DataDomain dataDomain) {
            return new CountingFlushAction(dataDomain, sorter, deleteDbRowOpFactory);
        }
    }

    static class CountingFlushAction extends DefaultDataDomainFlushAction {

        CountingFlushAction(DataDomain dataDomain, DbRowOpSorter sorter, DeleteDbRowOpFactory deleteDbRowOpFactory) {
            super(dataDomain, sorter, deleteDbRowOpFactory);
        }

        @Override
        protected List<DbRowOp> createDbRowOps(DataContextObjectStore objectStore, ObjectStoreGraphDiff changes) {
            CREATE_CALLS.incrementAndGet();
            return super.createDbRowOps(objectStore, changes);
        }
    }
}
