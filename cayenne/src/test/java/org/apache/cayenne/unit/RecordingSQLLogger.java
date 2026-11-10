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

package org.apache.cayenne.unit;

import org.apache.cayenne.access.translator.TranslatedStatement;
import org.apache.cayenne.log.SQLLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Records the SQL of the update statements a runtime runs, including the ones that fail, for a test to check which
 * statements a commit issued. Bind it with {@code CayenneTestsEnv.withExtraModules(binder ->
 * binder.bind(SQLLogger.class).toInstance(logger))}.
 */
public class RecordingSQLLogger implements SQLLogger {

    private final List<String> updates = new ArrayList<>();

    public void clear() {
        updates.clear();
    }

    public List<String> updates() {
        return updates;
    }

    /**
     * Returns the number of recorded statements of a kind ("UPDATE", "INSERT INTO", "DELETE FROM") on a table,
     * whether or not the adapter quotes its name.
     */
    public long statements(String kind, String table) {
        Pattern pattern = Pattern.compile("^\\s*" + kind + "\\s+[\"`\\[]?" + table + "[\"`\\]]?\\s",
                Pattern.CASE_INSENSITIVE);
        return updates.stream().filter(sql -> pattern.matcher(sql).find()).count();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void logSelect(TranslatedStatement statement, int rowCount, long durationMillis) {
    }

    @Override
    public void logUpdate(TranslatedStatement statement, int rowCount, List<? extends Map<String, ?>> generatedKeys,
                          long durationMillis) {
        updates.add(statement.sql());
    }

    @Override
    public void logQueryError(TranslatedStatement statement, Throwable error, long durationMillis) {
        updates.add(statement.sql());
    }

    @Override
    public void logAlsoSelect(int rowCount) {
    }

    @Override
    public void logAlsoUpdate(int rowCount) {
    }

    @Override
    public void logTransactionStart() {
    }

    @Override
    public void logTransactionCommit() {
    }

    @Override
    public void logTransactionRollback() {
    }

    @Override
    public void logMessage(String message) {
    }
}
