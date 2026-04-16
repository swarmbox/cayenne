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

package org.apache.cayenne.util;

import java.util.List;

import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;

/**
 * Structural questions about mapped relationships that the query translator, the flush and the prefetch code ask
 * alike. Intended for internal use; not part of the mapping API.
 *
 * @since 5.0
 */
public final class RelationshipPredicates {

    private RelationshipPredicates() {
    }

    /**
     * Returns whether the relationship is a full primary key identity join, meaning it joins on all primary key
     * columns of both entities.
     * <p>
     * This intentionally excludes partial PK joins such as join tables with composite primary keys (e.g. many-to-many
     * association tables where each FK column is part of the composite PK).
     * <p>
     * A join whose endpoints do not resolve is not an identity join either. This is deliberately stricter than
     * {@link DbRelationship#isValidForDepPk()}, which tolerates a null endpoint, because callers act on the answer by
     * skipping writes: treating an unresolved join as an identity join would drop a flattened row's DELETE or turn its
     * INSERT into an UPDATE matching nothing.
     */
    public static boolean isSharedPrimaryKey(DbRelationship relationship) {
        List<DbJoin> joins = relationship.getJoins();
        if (joins.isEmpty()) {
            return false;
        }
        for (DbJoin join : joins) {
            DbAttribute source = join.getSource();
            DbAttribute target = join.getTarget();
            if (source == null || target == null || !source.isPrimaryKey() || !target.isPrimaryKey()) {
                return false;
            }
        }
        int joinCount = joins.size();
        return joinCount == relationship.getSourceEntity().getPrimaryKeys().size()
                && joinCount == relationship.getTargetEntity().getPrimaryKeys().size();
    }

    /**
     * Returns whether the relationship leads from the dependent row of a PK-to-PK pair to its master row, which always
     * exists. As in DataRowUtils, the "to dependent PK" flag on the reverse is what says that the source is the
     * dependent.
     */
    public static boolean isToMasterPK(DbRelationship relationship) {
        if (relationship.isToMany() || relationship.isToDependentPK()) {
            return false;
        }
        DbRelationship reverse = relationship.getReverseRelationship();
        return reverse != null && reverse.isToDependentPK();
    }
}
