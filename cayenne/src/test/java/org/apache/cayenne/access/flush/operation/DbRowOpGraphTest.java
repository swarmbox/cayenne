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

import java.time.Duration;
import java.util.Set;

import org.apache.cayenne.GenericPersistentObject;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.map.DbEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DbRowOpGraphTest {

    private static final int CHAIN_LENGTH = 100_000;

    private final DbEntity entity = new DbEntity("row");
    private final Persistent object = new GenericPersistentObject();

    /**
     * A cycle left over after a long chain of operations was sorted is reported in time linear in the size of the
     * graph, naming only the operations of the cycle.
     */
    @Test
    public void cycleAfterALongSortedChainIsReportedQuickly() {
        DbRowOpGraph graph = new DbRowOpGraph();
        DbRowOp previous = op(0);
        graph.add(previous);
        for (int i = 1; i < CHAIN_LENGTH; i++) {
            DbRowOp next = op(i);
            graph.add(previous, next);
            previous = next;
        }
        DbRowOp cycleFirst = op(-1);
        DbRowOp cycleSecond = op(-2);
        graph.add(cycleFirst, cycleSecond);
        graph.add(cycleSecond, cycleFirst);

        IllegalStateException cycle = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IllegalStateException.class, graph::topSort));

        String prefix = "Cycle detected in list for keys: ";
        Set<String> expected = Set.of(
                prefix + "[" + cycleFirst + ", " + cycleSecond + "]",
                prefix + "[" + cycleSecond + ", " + cycleFirst + "]");
        assertTrue(expected.contains(cycle.getMessage()), cycle.getMessage());
    }

    private DbRowOp op(int id) {
        return new InsertDbRowOp(object, entity, ObjectId.of("Row", "id", id));
    }
}
