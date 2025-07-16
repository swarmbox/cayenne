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

import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.Validating;
import org.apache.cayenne.graph.ArcId;
import org.apache.cayenne.graph.CompoundDiff;
import org.apache.cayenne.graph.GraphChangeHandler;
import org.apache.cayenne.graph.GraphDiff;
import org.apache.cayenne.graph.NodeDiff;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.reflect.AttributeProperty;
import org.apache.cayenne.reflect.PropertyDescriptor;
import org.apache.cayenne.reflect.PropertyVisitor;
import org.apache.cayenne.reflect.ToManyProperty;
import org.apache.cayenne.reflect.ToOneProperty;
import org.apache.cayenne.validation.ValidationException;
import org.apache.cayenne.validation.ValidationResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * A GraphDiff facade for the ObjectStore changes. Provides a way for the lower
 * layers of the access stack to speed up processing of presorted ObjectStore
 * diffs.
 * 
 * @since 1.2
 */
public class ObjectStoreGraphDiff implements GraphDiff {

    private ObjectStore objectStore;
    private GraphDiff resolvedDiff;
    private int lastSeenDiffId;

    ObjectStoreGraphDiff(ObjectStore objectStore) {
        this.objectStore = objectStore;
        preprocess(objectStore);
    }

    public Map<Object, ObjectDiff> getChangesByObjectId() {
        return objectStore.getChangesByObjectId();
    }

    /**
     * Requires external synchronization on ObjectStore.
     */
    boolean validateAndCheckNoop() {
        if (getChangesByObjectId().isEmpty()) {
            return true;
        }

        boolean noop = true;

        // build a new collection for validation as validation methods may
        // result in
        // ObjectStore modifications

        Collection<Validating> objectsToValidate = null;

        for (final ObjectDiff diff : getChangesByObjectId().values()) {

            if (!diff.isNoop()) {

                noop = false;

                if (diff.getObject() instanceof Validating) {
                    if (objectsToValidate == null) {
                        objectsToValidate = new ArrayList<>();
                    }

                    objectsToValidate.add((Validating) diff.getObject());
                }

            }
        }

        if (objectsToValidate != null) {
            ValidationResult result = new ValidationResult();

            for (Validating object : objectsToValidate) {
                switch (((Persistent) object).getPersistenceState()) {
                case PersistenceState.NEW:
                    object.validateForInsert(result);
                    break;
                case PersistenceState.MODIFIED:
                    object.validateForUpdate(result);
                    break;
                case PersistenceState.DELETED:
                    object.validateForDelete(result);
                    break;
                }
            }

            if (result.hasFailures()) {
                throw new ValidationException(result);
            }
        }

        return noop;
    }

    @Override
    public boolean isNoop() {
        if (getChangesByObjectId().isEmpty()) {
            return true;
        }

        for (ObjectDiff diff : getChangesByObjectId().values()) {
            if (!diff.isNoop()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void apply(GraphChangeHandler handler) {
        resolveDiff();
        resolvedDiff.apply(handler);
    }

    @Override
    public void undo(GraphChangeHandler handler) {
        resolveDiff();
        resolvedDiff.undo(handler);
    }

    /**
     * Converts diffs organized by ObjectId in a collection of diffs sorted by
     * diffId (same as creation order).
     */
	private void resolveDiff() {

		// refresh the diff on first access or if the underlying ObjectStore has
		// changed the the last time we cached the changes.
		if (resolvedDiff == null || lastSeenDiffId < objectStore.currentDiffId) {

			CompoundDiff diff = new CompoundDiff();
			Map<Object, ObjectDiff> changes = getChangesByObjectId();

			if (!changes.isEmpty()) {
				List<NodeDiff> allChanges = new ArrayList<>(changes.size() * 2);

				for (final ObjectDiff objectDiff : changes.values()) {
					objectDiff.appendDiffs(allChanges);
				}

				Collections.sort(allChanges);
				diff.addAll(allChanges);

			}

			this.lastSeenDiffId = objectStore.currentDiffId;
			this.resolvedDiff = diff;
		}
	}

    private void preprocess(ObjectStore objectStore) {

        Map<Object, ObjectDiff> changes = getChangesByObjectId();
        if (!changes.isEmpty()) {

            for (Entry<Object, ObjectDiff> entry : changes.entrySet()) {

                ObjectId id = (ObjectId) entry.getKey();

                Persistent object = (Persistent) objectStore.getNode(id);

                // address manual id override.
                ObjectId objectId = object.getObjectId();
                if (!id.equals(objectId)) {

                    if (objectId != null) {
                        Map<String, Object> replacement = id.getReplacementIdMap();
                        replacement.clear();
                        replacement.putAll(objectId.getIdSnapshot());
                    }

                    object.setObjectId(id);
                }
            }
        }
    }

    /**
     * This avoids the error of performing an insert when there should be an update on flattened relationships,
     * by sorting the changes to the side that works correctly to the start.
     * This is a temporary solution for use by SwarmBox internally until CAY-2890 is resolved.
     */
    public Map<Object, ObjectDiff> getSortedChangesByObjectId() {
        return getChangesByObjectId().entrySet().stream()
                                     .map(SortEntry::fromChangeEntry)
                                     .sorted()
                                     .collect(
                                         LinkedHashMap::new,
                                         (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                                         Map::putAll
                                     );
    }

    /** Self-contained sort entry that holds both the map entry data and its sort criteria. */
    private static class SortEntry implements Comparable<SortEntry> {
        private final Object key;
        private final ObjectDiff value;
        private final boolean hasToOneOnDependentEntity;
        private final boolean hasToOneToForeignKey;

        private SortEntry(
            Object key,
            ObjectDiff value,
            boolean hasToOneOnDependentEntity,
            boolean hasToOneToForeignKey
        ) {
            this.key = key;
            this.value = value;
            this.hasToOneOnDependentEntity = hasToOneOnDependentEntity;
            this.hasToOneToForeignKey = hasToOneToForeignKey;
        }

        static SortEntry fromChangeEntry(Map.Entry<Object, ObjectDiff> entry) {
            SortCriteriaExtractor extractor = SortCriteriaExtractor.fromObjectDiff(entry.getValue());
            return new SortEntry(
                entry.getKey(),
                entry.getValue(),
                extractor.hasToOneOnDependentEntity(),
                extractor.hasToOneToForeignKey()
            );
        }

        Object getKey() {
            return key;
        }

        ObjectDiff getValue() {
            return value;
        }

        @Override
        public int compareTo(SortEntry other) {
            // Sort ToOne on dependent entities first (reversed boolean comparison)
            int depComparison = Boolean.compare(other.hasToOneOnDependentEntity, this.hasToOneOnDependentEntity);
            if (depComparison != 0) {
                return depComparison;
            }

            // Then sort ToOne to FK first (reversed boolean comparison)
            return Boolean.compare(other.hasToOneToForeignKey, this.hasToOneToForeignKey);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;

            SortEntry other = (SortEntry) obj;
            return hasToOneOnDependentEntity == other.hasToOneOnDependentEntity &&
                   hasToOneToForeignKey == other.hasToOneToForeignKey &&
                   Objects.equals(key, other.key) &&
                   Objects.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value, hasToOneOnDependentEntity, hasToOneToForeignKey);
        }
    }

    /** Extracts sorting criteria from an ObjectDiff by analyzing its arc operations */
    private static class SortCriteriaExtractor implements GraphChangeHandler {
        private final ObjectDiff diff;
        private boolean foundToOneOnDependentEntity = false;
        private boolean foundToOneToForeignKey = false;

        private SortCriteriaExtractor(ObjectDiff diff) {
            this.diff = Objects.requireNonNull(diff);
        }

        static SortCriteriaExtractor fromObjectDiff(ObjectDiff objectDiff) {
            SortCriteriaExtractor extractor = new SortCriteriaExtractor(objectDiff);
            objectDiff.apply(extractor);
            return extractor;
        }

        boolean hasToOneOnDependentEntity() {
            return foundToOneOnDependentEntity;
        }

        boolean hasToOneToForeignKey() {
            return foundToOneToForeignKey;
        }

        @Override
        public void arcCreated(Object nodeId, Object targetNodeId, ArcId arcId) {
            processArcChange(nodeId, arcId);
        }

        @Override
        public void arcDeleted(Object nodeId, Object targetNodeId, ArcId arcId) {
            processArcChange(nodeId, arcId);
        }

        private void processArcChange(Object nodeId, ArcId arcId) {
            // Skip if both flags were found already
            if (foundToOneOnDependentEntity && foundToOneToForeignKey) {
                return;
            }

            // Only process arcs for the current diff's node
            if (!diff.getNodeId().equals(nodeId)) {
                return;
            }

            PropertyDescriptor property = diff.getClassDescriptor()
                                              .getDeclaredProperty(arcId.getForwardArc());

            if (property == null) {
                return;
            }

            // Check if this is a ToOne relationship on a dependent entity
            if (!foundToOneOnDependentEntity) {
                foundToOneOnDependentEntity = property.visit(ToOneDependentEntityChecker.INSTANCE);
            }

            // Check if this is a ToOne relationship to a FK target
            if (!foundToOneToForeignKey) {
                foundToOneToForeignKey = property.visit(ToOneToForeignKeyChecker.INSTANCE);
            }
        }
    }

    /** Checks if a property is a ToOne relationship on a dependent entity */
    private static class ToOneDependentEntityChecker implements PropertyVisitor {
        static final ToOneDependentEntityChecker INSTANCE = new ToOneDependentEntityChecker();

        private ToOneDependentEntityChecker() {} // Singleton

        @Override
        public boolean visitAttribute(AttributeProperty property) {
            return false;
        }

        @Override
        public boolean visitToOne(ToOneProperty property) {
            return property.getRelationship().isToDependentEntity();
        }

        @Override
        public boolean visitToMany(ToManyProperty property) {
            return false;
        }
    }

    /** Checks if a property is a ToOne relationship targeting a foreign key */
    private static class ToOneToForeignKeyChecker implements PropertyVisitor {
        static final ToOneToForeignKeyChecker INSTANCE = new ToOneToForeignKeyChecker();

        private ToOneToForeignKeyChecker() {} // Singleton

        @Override
        public boolean visitAttribute(AttributeProperty property) {
            return false;
        }

        @Override
        public boolean visitToOne(ToOneProperty property) {
            ObjRelationship objRelationship = property.getRelationship();
            List<DbRelationship> dbRelationships = objRelationship.getDbRelationships();

            // Check if any database relationship is to a non-PK and not to-many
            for (DbRelationship dbRelationship : dbRelationships) {
                if (!dbRelationship.isToPK() && !dbRelationship.isToMany()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean visitToMany(ToManyProperty property) {
            return false;
        }
    }
}
