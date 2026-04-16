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

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.exp.Expression;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.JoinType;

/**
 * @since 4.2
 */
class TableTree {

    public static final String CURRENT_ALIAS = "__current_table_alias__";
    public static final CayennePath CURRENT_ALIAS_PATH = CayennePath.of("__current_table_alias__");

    /**
     * Tables mapped by db path it's spawned by.
     * Can be following:
     * - query root table
     * - tables from flattened attributes (including all intermediate tables)
     * - tables from attributes used in expressions (WHERE, HAVING, ORDER BY)
     * - tables from prefetches
     */
    private final Map<CayennePath, TableTreeNode> tableNodes;

    /**
     * Aliases that have been referenced by content-producing stages (SELECT
     * columns, WHERE qualifiers, ORDER BY, etc.) via {@link #aliasForPath}.
     */
    private final Set<String> usedAliases;

    private final TableTree parentTree;
    private final TableTreeNode rootNode;
    private final JoinRedirectProcessor redirectProcessor;

    private TableTreeNode activeNode;
    private int tableAliasSequence;

    TableTree(DbEntity root, TableTree parentTree) {
        this.tableAliasSequence = 0;
        this.parentTree = parentTree;
        this.tableNodes = new LinkedHashMap<>();
        this.usedAliases = new HashSet<>();
        this.rootNode = new TableTreeNode(root, nextTableAlias());
        this.redirectProcessor = new JoinRedirectProcessor(tableNodes::get, rootNode);
    }

    void addJoinTable(CayennePath path, DbRelationship relationship, JoinType joinType) {
        addJoinTable(path, relationship, joinType, null);
    }

    void addJoinTable(CayennePath path, DbRelationship relationship, JoinType joinType, Expression additionalQualifier) {
        if (tableNodes.containsKey(path)) {
            return;
        }
        if (redirectProcessor.redirectFor(path) != null) {
            return;
        }

        AliasRedirect newRedirect = redirectProcessor.detect(path, relationship, joinType, additionalQualifier);
        if (newRedirect != null) {
            usedAliases.add(newRedirect.alias);
            return;
        }

        TableTreeNode node = new TableTreeNode(path, relationship, nextTableAlias(), joinType, additionalQualifier);
        tableNodes.put(path, node);
    }

    String aliasForPath(CayennePath attributePath) {
        if(attributePath.isEmpty()) {
            return rootNode.getTableAlias();
        }
        // should be resolved dynamically by the caller
        if(CURRENT_ALIAS_PATH.equals(attributePath)) {
            return CURRENT_ALIAS;
        }
        String alias = resolveAlias(attributePath);
        if (alias == null) {
            throw new CayenneRuntimeException("No table for attribute '%s' found", attributePath);
        }
        usedAliases.add(alias);
        return alias;
    }

    private String resolveAlias(CayennePath path) {
        if (path.isEmpty()) {
            return rootNode.getTableAlias();
        }
        AliasRedirect redirect = redirectProcessor.redirectFor(path);
        if (redirect != null) {
            return redirect.alias;
        }
        TableTreeNode node = tableNodes.get(path);
        if (node != null) {
            return node.getTableAlias();
        }
        return null;
    }

    boolean isAliasUsed(String alias) {
        return usedAliases.contains(alias);
    }

    void prune(Collection<CayennePath> paths) {
        for (CayennePath path : paths) {
            tableNodes.remove(path);
        }
    }

    String nextTableAlias() {
        // delegate actual generation to parent if any
        if(parentTree != null) {
            return parentTree.nextTableAlias();
        }
        return 't' + String.valueOf(tableAliasSequence++);
    }

    TableTreeNode nonNullActiveNode() {
        if(activeNode == null) {
            throw new CayenneRuntimeException("No active TableTree node found");
        }
        return activeNode;
    }

    void setActiveNode(TableTreeNode activeNode) {
        this.activeNode = activeNode;
    }

    public int getNodeCount() {
        return tableNodes.size() + 1;
    }

    boolean hasToManyJoin() {
        if(getNodeCount() <= 1) {
            return false;
        }

        AtomicBoolean atomicBoolean = new AtomicBoolean(false);
        visit(node -> {
            if(node.getRelationship() != null && node.getRelationship().isToMany()) {
                atomicBoolean.set(true);
            }
        });
        return atomicBoolean.get();
    }

    public void visit(TableNodeVisitor visitor) {
        visitor.visit(rootNode);

        // as we can spawn new nodes while processing existing,
        // we need multiple iterations until all rows are processed
        int initialSize = 0;
        int currentSize = tableNodes.size();
        while(initialSize != currentSize) {
            tableNodes.values().stream().skip(initialSize)
                    .collect(Collectors.toList()) // copy collection in case of concurrent modification in visitor
                    .forEach(visitor::visit);
            initialSize = currentSize;
            currentSize = tableNodes.size();
        }
    }

    @FunctionalInterface
    interface TableNodeVisitor {
        void visit(TableTreeNode node);
    }
}
