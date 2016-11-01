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
package org.apache.cayenne.access;

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.query.SelectQuery;
import org.apache.cayenne.test.jdbc.DBHelper;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical_sb.*;
import org.apache.cayenne.unit.di.server.ServerCase;
import org.apache.cayenne.unit.di.server.UseServerRuntime;
import org.junit.Test;

import java.sql.SQLException;

import static org.junit.Assert.*;

//TODO Rename all tables to something more generalized

@UseServerRuntime("cayenne-inheritance-vertical-sb.xml")
public class VerticalInheritanceSB extends ServerCase {

	@Inject
	protected ObjectContext context;

	@Inject
	protected DBHelper dbHelper;

	private static final int DEFAULT_PERSON_ID = 1;
	private static final String DEFAULT_PERSON_REFERENCE = "p1";
	private static final String DEFAULT_PERSON_NAME = "John";

	@Test
	public void testPersonOneToManyUpdate() throws Exception {
		setupDefaultPerson();

		TableHelper personTable = new TableHelper(dbHelper, "iv_person");

		TableHelper familyTable = new TableHelper(dbHelper, "iv_family");
		familyTable.setColumns("id", "last_name");

		familyTable.insert(5, "Smith");

		IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
		IvFamily family = context.selectOne(new SelectQuery<>(IvFamily.class));

		person.setFamily(family);

		context.commitChanges();

		assertEquals(person.getFamily(), family);
		assertEquals(personTable.getInt("family_id"), 5);
	}

	@Test
	public void testPersonHasFKOneToOneUpdateFromPerson() throws Exception { //Person has FK to drivers_license
		setupDefaultPerson();

		TableHelper personTable = new TableHelper(dbHelper, "iv_person");

		TableHelper driversLicenseTable = new TableHelper(dbHelper, "iv_drivers_license");
		driversLicenseTable.setColumns("id", "reference");

		driversLicenseTable.insert(5, "123ABC");

		IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
		IvDriversLicense dl = context.selectOne(new SelectQuery<>(IvDriversLicense.class));

		person.setDriversLicense(dl);

		context.commitChanges();

		assertEquals(person.getDriversLicense(), dl);
		assertEquals(personTable.getInt("drivers_license_id"), 5);
	}

	@Test
	public void testPersonHasFKOneToOneUpdateFromDriversLicense() throws Exception {
		setupDefaultPerson();

		TableHelper personTable = new TableHelper(dbHelper, "iv_person");

		TableHelper driversLicenseTable = new TableHelper(dbHelper, "iv_drivers_license");
		driversLicenseTable.setColumns("id", "reference");

		driversLicenseTable.insert(5, "123ABC");

		IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
		IvDriversLicense dl = context.selectOne(new SelectQuery<>(IvDriversLicense.class));

		dl.setPerson(person);

		context.commitChanges();

		assertEquals(person.getDriversLicense(), dl);
		assertEquals(personTable.getInt("drivers_license_id"), 5);
	}

	@Test
	public void testPersonOneToOneUpdateFromAgronomist() throws Exception { //Agronomist has FK to person
		setupDefaultPerson();

		TableHelper agronomistTable = new TableHelper(dbHelper, "iv_agronomist");
		agronomistTable.setColumns("id", "plants_planted");

		agronomistTable.insert(5, 100);

		IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
		IvAgronomist agronomist = context.selectOne(new SelectQuery<>(IvAgronomist.class));

		agronomist.setPerson(person);

		context.commitChanges();

		assertEquals(agronomist.getPerson(), person);
		assertEquals(agronomistTable.getInt("person_id"), 1);
	}

	@Test
	public void testPersonOneToOneUpdateFromPerson() throws Exception { //Agronomist has FK to person
		setupDefaultPerson();

		TableHelper agronomistTable = new TableHelper(dbHelper, "iv_agronomist");
		agronomistTable.setColumns("id", "plants_planted");

		agronomistTable.insert(5, 100);

		IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
		IvAgronomist agronomist = context.selectOne(new SelectQuery<>(IvAgronomist.class));

		person.setAgronomist(agronomist);

		context.commitChanges();

		assertEquals(agronomist.getPerson(), person);
		assertEquals(agronomistTable.getInt("person_id"), 1);
	}
	@Test
	public void testAbstractHasInheritance() throws Exception { //TODO May be redundant with test from another inheritance suite.
		setupDefaultPerson();



		TableHelper emailTable = new TableHelper(dbHelper, "iv_email");
		emailTable.setColumns("id", "address");

		emailTable.insert(5, "a@b.com");

		IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
		IvEmail email = context.selectOne(new SelectQuery<>(IvEmail.class));

		email.setEntity(person);

		context.commitChanges();

		assertEquals(email.getEntity(), person);
		assertEquals(emailTable.getInt("entity_id"), 1);
	}

	private void setupDefaultPerson() throws SQLException {
		TableHelper entityTable = new TableHelper(dbHelper, "iv_entity");
		entityTable.setColumns("id", "reference", "type");

		TableHelper personTable = new TableHelper(dbHelper, "iv_person");
		personTable.setColumns("id", "name");

		entityTable.insert(DEFAULT_PERSON_ID, DEFAULT_PERSON_REFERENCE, "P");
		personTable.insert(DEFAULT_PERSON_ID, DEFAULT_PERSON_NAME);
	}

	//TODO Test creating obj that starts with inheritance relationship
	//Test nullifying relationship
	//Test multiple inheritance

}
