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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.exp.path.CayennePathSegment;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjAttribute;
import org.apache.cayenne.map.ObjEntity;

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
     * Returns whether the relationship joins on all primary key columns of its target, so that the database guarantees
     * it matches at most one target row. A to-one over other columns relies on a uniqueness the mapping only declares.
     */
    public static boolean isToFullPrimaryKey(DbRelationship relationship) {
        List<DbJoin> joins = relationship.getJoins();
        if (joins.isEmpty()) {
            return false;
        }
        Set<DbAttribute> targets = new HashSet<>();
        for (DbJoin join : joins) {
            DbAttribute target = join.getTarget();
            if (target == null || !target.isPrimaryKey()) {
                return false;
            }
            targets.add(target);
        }
        return targets.containsAll(relationship.getTargetEntity().getPrimaryKeys());
    }

    /**
     * Returns whether the relationship leads from the dependent row of a PK-to-PK pair to its master row. As in
     * DataRowUtils, the "to dependent PK" flag on the reverse is what says that the source is the dependent.
     */
    public static boolean isToMasterPK(DbRelationship relationship) {
        if (relationship.isToMany() || relationship.isToDependentPK()) {
            return false;
        }
        DbRelationship reverse = relationship.getReverseRelationship();
        return reverse != null && reverse.isToDependentPK();
    }

    /**
     * Returns whether the relationship leads from a vertical inheritance sub-table row to its super-table row: it
     * leads to the master row of a PK-to-PK pair, and an ObjEntity with a super-entity maps attributes over the reverse
     * relationship, at any depth of the hierarchy.
     * <p>
     * Such a super-table row is assumed to exist, as Cayenne writes it together with the sub-table row. A sub-table
     * row written without it outside Cayenne breaks that assumption. Other PK-to-PK pairs carry no such guarantee.
     */
    public static boolean isVerticalInheritanceLink(DbRelationship relationship) {
        if (!isToMasterPK(relationship)) {
            return false;
        }
        DataMap map = relationship.getTargetEntity().getDataMap();
        if (map == null) {
            return false;
        }
        DbRelationship toSubTable = relationship.getReverseRelationship();
        Collection<ObjEntity> entities = map.getNamespace() != null
                ? map.getNamespace().getObjEntities()
                : map.getObjEntities();
        for (ObjEntity entity : entities) {
            if (entity.getSuperEntity() != null && mapsAttributesOver(entity, toSubTable)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether a db attribute path leads from the entity over the relationship to an attribute. The path is
     * walked by name, so that a path that doesn't resolve, e.g. one whose column or relationship was removed from the
     * mapping, leads over no relationship rather than failing.
     */
    private static boolean leadsOver(DbEntity entity, CayennePath dbAttributePath, DbRelationship relationship) {
        if (entity == null || dbAttributePath == null || dbAttributePath.isEmpty()) {
            return false;
        }

        List<CayennePathSegment> segments = dbAttributePath.segments();
        int last = segments.size() - 1;
        boolean overRelationship = false;
        DbEntity current = entity;
        for (CayennePathSegment segment : segments.subList(0, last)) {
            DbRelationship step = current.getRelationship(segment.value());
            if (step == null || step.getTargetEntity() == null) {
                return false;
            }
            if (step == relationship) {
                overRelationship = true;
            }
            current = step.getTargetEntity();
        }

        boolean endsAtAttribute = current.getAttribute(segments.get(last).value()) != null;
        return overRelationship && endsAtAttribute;
    }

    private static boolean mapsAttributesOver(ObjEntity entity, DbRelationship relationship) {
        for (ObjAttribute attribute : entity.getDeclaredAttributes()) {
            if (leadsOver(entity.getDbEntity(), attribute.getDbAttributePath(), relationship)) {
                return true;
            }
        }
        return false;
    }
}
