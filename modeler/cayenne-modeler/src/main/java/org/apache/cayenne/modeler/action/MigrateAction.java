/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/

package org.apache.cayenne.modeler.action;

import org.apache.cayenne.dba.DbAdapter;
import org.apache.cayenne.dbsync.merge.factory.MergerTokenFactoryProvider;
import org.apache.cayenne.dbsync.reverse.db.DbLoader;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.modeler.Application;
import org.apache.cayenne.modeler.dialog.db.DataSourceController;
import org.apache.cayenne.modeler.dialog.db.DbMigrateOptionsDialog;
import org.apache.cayenne.modeler.dialog.db.MergerOptions;
import org.apache.cayenne.modeler.pref.DBConnectionInfo;

import javax.sql.DataSource;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * Action that alter database schema to match a DataMap.
 */
public class MigrateAction extends DBWizardAction {

    public MigrateAction(Application application) {
        super(getActionName(), application);
    }

    public static String getActionName() {
        return "Migrate Database Schema";
    }

    public void performAction(ActionEvent e) {

        DBConnectionInfo nodeInfo = preferredDataSource();
        String nodeKey = preferredDataSourceLabel(nodeInfo);

        DataSourceController connectWizard = new DataSourceController(
                getProjectController(),
                "Migrate DB Schema: Connect to Database",
                nodeKey,
                nodeInfo);

        if (!connectWizard.startupAction()) {
            // canceled
            return;
        }

        DataMap map = getProjectController().getCurrentDataMap();
        //migarte options

        // sanity check
        if (map == null) {
            throw new IllegalStateException("No current DataMap selected.");
        }
        //showOptions dialog
        String selectedSchema = null;
        try {
            List<String> schemas = getSchemas(connectWizard);
            if (schemas != null && !schemas.isEmpty()) {
                DbMigrateOptionsDialog optionsDialog = new DbMigrateOptionsDialog(schemas, connectWizard.getConnectionInfo().getUserName());
                optionsDialog.showDialog();
                if (optionsDialog.getChoice() == DbMigrateOptionsDialog.SELECT) {
                    selectedSchema = optionsDialog.getSelectedSchema();
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                    Application.getFrame(),
                    ex.getMessage(),
                    "Error loading schemas dialog",
                    JOptionPane.ERROR_MESSAGE);
        }

        MergerTokenFactoryProvider mergerTokenFactoryProvider =
                getApplication().getInjector().getInstance(MergerTokenFactoryProvider.class);

        // ... show dialog...
        new MergerOptions(
                getProjectController(),
                "Migrate DB Schema: Options",
                connectWizard.getConnectionInfo(),
                map, selectedSchema, mergerTokenFactoryProvider).startupAction();
    }

    private List<String> getSchemas(DataSourceController connectWizard) throws Exception {
        DbAdapter dbAdapter = connectWizard.getConnectionInfo()
                .makeAdapter(getApplication().getClassLoadingService());
        DataSource dataSource = connectWizard.getConnectionInfo()
                .makeDataSource(getApplication().getClassLoadingService());

        return DbLoader.loadSchemas(dataSource.getConnection());
    }
}
