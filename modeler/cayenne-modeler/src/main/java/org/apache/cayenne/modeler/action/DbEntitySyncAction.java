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

import org.apache.cayenne.configuration.DataChannelDescriptor;
import org.apache.cayenne.dbsync.merge.EntityMergeSupport;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.map.event.EntityEvent;
import org.apache.cayenne.map.event.MapEvent;
import org.apache.cayenne.modeler.Application;
import org.apache.cayenne.modeler.ProjectController;
import org.apache.cayenne.modeler.dialog.objentity.EntitySyncController;
import org.apache.cayenne.modeler.undo.DbEntitySyncUndoableEdit;
import org.apache.cayenne.modeler.util.CayenneAction;

import java.awt.event.ActionEvent;
import java.util.Collection;

/**
 * Action that synchronizes all ObjEntities with the current state of the
 * selected DbEntity.
 */
public class DbEntitySyncAction extends CayenneAction {

	public static String getActionName() {
		return "Sync Dependent ObjEntities with DbEntity";
	}

	public DbEntitySyncAction(Application application) {
		super(getActionName(), application);
	}

	public String getIconName() {
		return "icon-sync.gif";
	}

	/**
	 * @see org.apache.cayenne.modeler.util.CayenneAction#performAction(ActionEvent)
	 */
	public void performAction(ActionEvent e) {
		synchDbEntity();
	}

	protected void synchDbEntity() {
		ProjectController mediator = getProjectController();

		DbEntity dbEntity = mediator.getCurrentDbEntity();

		if (dbEntity != null) {

			Collection<ObjEntity> entities = dbEntity.getDataMap().getMappedEntities(dbEntity);
			if (entities.isEmpty()) {
				return;
			}

			EntityMergeSupport merger = new EntitySyncController(Application.getInstance().getFrameController(),
					dbEntity).createMerger();

			if (merger == null) {
				return;
			}

			DbEntitySyncUndoableEdit undoableEdit = new DbEntitySyncUndoableEdit((DataChannelDescriptor) mediator
					.getProject().getRootNode(), mediator.getCurrentDataMap());


			for(ObjEntity entity : entities) {

				DbEntitySyncUndoableEdit.EntitySyncUndoableListener listener = undoableEdit.new EntitySyncUndoableListener(
						entity);

				merger.addEntityMergeListener(listener);

				// TODO: addition or removal of model objects should be reflected in listener callbacks...
				// we should not be trying to introspect the merger
				if (merger.isRemovingMeaningfulFKs()) {
					undoableEdit.addEdit(undoableEdit.new MeaningfulFKsUndoableEdit(entity, merger
							.getMeaningfulFKs(entity)));
				}

				if (merger.synchronizeWithDbEntity(entity)) {
					mediator.fireObjEntityEvent(new EntityEvent(this, entity, MapEvent.CHANGE));
				}

				merger.removeEntityMergeListener(listener);
			}

			application.getUndoManager().addEdit(undoableEdit);
		}
	}
}
