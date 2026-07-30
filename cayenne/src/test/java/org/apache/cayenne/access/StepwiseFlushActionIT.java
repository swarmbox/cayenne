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

import org.apache.cayenne.access.flush.DataDomainFlushActionFactory;
import org.apache.cayenne.testdo.testmap.RWCompoundPainting;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A flush action whose flush() composes the protected steps itself updates the flattened attribute row its earlier
 * flush inserted.
 */
public class StepwiseFlushActionIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT)
            .withExtraModules(b -> b.bind(DataDomainFlushActionFactory.class).to(StepwiseFlushAction.Factory.class));

    @Test
    public void flattenedAttributeRowInsertedEarlierIsUpdated() throws Exception {
        DataContext context = env.context();
        RWCompoundPainting painting = context.newObject(RWCompoundPainting.class);
        painting.setPaintingTitle("p1");
        painting.setTextReview("v1");
        context.commitChanges();

        painting.setTextReview("v2");
        context.commitChanges();

        assertEquals("v2", env.table("PAINTING_INFO", "TEXT_REVIEW").getString("TEXT_REVIEW"));
    }
}
