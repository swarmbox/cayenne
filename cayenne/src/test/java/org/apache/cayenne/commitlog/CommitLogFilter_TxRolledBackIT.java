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

package org.apache.cayenne.commitlog;

import org.apache.cayenne.CayenneRuntimeBuilder;
import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.CoreModule;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.commitlog.db.AuditLog;
import org.apache.cayenne.commitlog.db.Auditable2;
import org.apache.cayenne.commitlog.unit.AuditableRuntimeCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A commit-log listener commits its own context inside the transaction of the commit it logs, and then the
 * transaction fails. Neither commit may leave anything behind: not the logged objects, not the listener's.
 */
public class CommitLogFilter_TxRolledBackIT extends AuditableRuntimeCase {

	private static final String INJECTED_FAILURE = "injected failure after the listener's commit";

	private ObjectContext context;
	private Auditable2 logged;
	private ObjectId loggedId;
	private int loggedStateInTx;
	private AuditLog log;
	private ObjectId logId;

	@Override
	protected CayenneRuntimeBuilder configureCayenne() {
		CommitLogListener listener = (originatingContext, changes) -> {
			// the listener's own commit is logged too, with no changes of annotated entities
			if (changes.getUniqueChanges().isEmpty()) {
				return;
			}

			// asserted once the transaction has ended: an assertion error thrown inside it would leave it open
			loggedId = logged.getObjectId();
			loggedStateInTx = logged.getPersistenceState();

			log = runtime.newContext().newObject(AuditLog.class);
			log.setLog("logged " + loggedId);
			log.getObjectContext().commitChanges();
			logId = log.getObjectId();

			// stands in for a later commit in the transaction, or a constraint deferred to COMMIT, failing
			throw new CayenneRuntimeException(INJECTED_FAILURE);
		};
		return super.configureCayenne().
				addModule(b -> CoreModule.extend(b).commitLogAnnotationEntitiesOnly().addCommitLogListener(listener));
	}

	@BeforeEach
	public void before() {
		this.context = runtime.newContext();
	}

	@Test
	public void listenerCommitLeavesNoPhantom() throws SQLException {
		logged = context.newObject(Auditable2.class);
		logged.setCharProperty1("yy");
		logged.setCharProperty2("zz");

		CayenneRuntimeException e = assertThrows(CayenneRuntimeException.class, context::commitChanges);
		assertTrue(e.getMessage().contains(INJECTED_FAILURE), () -> "unexpected failure: " + e);

		// inside the transaction the logged commit was visible as committed, under its permanent id (CAY-766)
		assertEquals(PersistenceState.COMMITTED, loggedStateInTx);
		assertFalse(loggedId.isTemporary());

		assertEquals(0, auditable2.getRowCount(), "precondition: the database was rolled back");
		assertEquals(0, auditLog.getRowCount(), "precondition: the database was rolled back");

		assertEquals(PersistenceState.TRANSIENT, logged.getPersistenceState());
		assertEquals(PersistenceState.TRANSIENT, log.getPersistenceState());
		assertFalse(context.hasChanges());

		// resolved through the shared cache, where the phantoms would be found without a query
		ObjectContext fresh = runtime.newContext();
		assertNull(fresh.objectForPK(loggedId));
		assertNull(fresh.objectForPK(logId));
	}
}
