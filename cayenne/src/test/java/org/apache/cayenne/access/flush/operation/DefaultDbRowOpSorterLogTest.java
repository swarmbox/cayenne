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

package org.apache.cayenne.access.flush.operation;

import java.util.ArrayList;
import java.util.List;

import org.apache.cayenne.ObjectId;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.map.DbEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public class DefaultDbRowOpSorterLogTest {

    @Test
    public void runDescriptionNamesItsEntitiesAndFirstFewOperations() {
        DbEntity first = new DbEntity("first");
        DbEntity second = new DbEntity("second");
        List<DbRowOp> run = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            ObjectId firstId = ObjectId.of("First", "id", i);
            ObjectId secondId = ObjectId.of("Second", "id", i);
            run.add(new InsertDbRowOp(mock(Persistent.class), first, firstId));
            run.add(new InsertDbRowOp(mock(Persistent.class), second, secondId));
        }

        String description = DefaultDbRowOpSorter.describeRun(run);

        assertTrue(description.startsWith("600 operations on [first, second]: "), description);
        assertTrue(description.endsWith(" and 595 more"), description);
        assertEquals(5, description.split("insert ").length - 1, description);
    }
}
