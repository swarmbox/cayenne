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
import org.apache.cayenne.testdo.inheritance_vertical_sb.Agronomist;
import org.apache.cayenne.testdo.inheritance_vertical_sb.DriversLicense;
import org.apache.cayenne.testdo.inheritance_vertical_sb.Family;
import org.apache.cayenne.testdo.inheritance_vertical_sb.Person;
import org.apache.cayenne.unit.di.server.CayenneProjects;
import org.apache.cayenne.unit.di.server.ServerCase;
import org.apache.cayenne.unit.di.server.UseServerRuntime;
import org.junit.Test;

import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@UseServerRuntime("cayenne-inheritance-vertical-sb.xml")
public class VerticalInheritanceSB extends ServerCase {

	@Inject
	protected ObjectContext context;

	@Inject
	protected DBHelper dbHelper;

	@Test
	public void testPersonOneToManyUpdate() throws Exception {
		TableHelper entityTable = new TableHelper(dbHelper, "entity");
		entityTable.setColumns("id", "reference", "type");

		TableHelper personTable = new TableHelper(dbHelper, "person");
		personTable.setColumns("id", "name");

		TableHelper familyTable = new TableHelper(dbHelper, "family");
		familyTable.setColumns("id", "last_name");

		entityTable.insert(1, "p1", "P");
		personTable.insert(1, "Doug");
		familyTable.insert(5, "Stuart");

		Person person = context.selectOne(new SelectQuery<>(Person.class, Person.NAME.eq("Doug")));
		Family family = context.selectOne(new SelectQuery<>(Family.class, Family.LAST_NAME.eq("Stuart")));

		person.setFamily(family);

		context.commitChanges();

		assertEquals(person.getFamily(), family);
		assertEquals(personTable.getInt("family_id"), 5);
	}

	@Test
	public void testPersonHasFKOneToOneUpdateFromPerson() throws Exception { //Person has FK to drivers_license
		TableHelper entityTable = new TableHelper(dbHelper, "entity");
		entityTable.setColumns("id", "reference", "type");

		TableHelper personTable = new TableHelper(dbHelper, "person");
		personTable.setColumns("id", "name");

		TableHelper driversLicenseTable = new TableHelper(dbHelper, "drivers_license");
		driversLicenseTable.setColumns("id", "reference");

		entityTable.insert(1, "p1", "P");
		personTable.insert(1, "Doug");
		driversLicenseTable.insert(5, "123ABC");

		Person person = context.selectOne(new SelectQuery<>(Person.class, Person.NAME.eq("Doug")));
		DriversLicense dl = context.selectOne(new SelectQuery<>(DriversLicense.class, DriversLicense.REFERENCE.eq("123ABC")));

		person.setDriversLicense(dl);

		context.commitChanges();

		assertEquals(person.getDriversLicense(), dl);
		assertEquals(personTable.getInt("drivers_license_id"), 5);
	}

	@Test
	public void testPersonHasFKOneToOneUpdateFromDriversLicense() throws Exception {
		TableHelper entityTable = new TableHelper(dbHelper, "entity");
		entityTable.setColumns("id", "reference", "type");

		TableHelper personTable = new TableHelper(dbHelper, "person");
		personTable.setColumns("id", "name");

		TableHelper driversLicenseTable = new TableHelper(dbHelper, "drivers_license");
		driversLicenseTable.setColumns("id", "reference");

		entityTable.insert(1, "p1", "P");
		personTable.insert(1, "Doug");
		driversLicenseTable.insert(5, "123ABC");

		Person person = context.selectOne(new SelectQuery<>(Person.class, Person.NAME.eq("Doug")));
		DriversLicense dl = context.selectOne(new SelectQuery<>(DriversLicense.class, DriversLicense.REFERENCE.eq("123ABC")));

		dl.setPerson(person);

		context.commitChanges();

		assertEquals(person.getDriversLicense(), dl);
		assertEquals(personTable.getInt("drivers_license_id"), 5);
	}

	@Test
	public void testPersonOneToOneUpdateFromAgronomist() throws Exception { //Agronomist has FK to person
		TableHelper entityTable = new TableHelper(dbHelper, "entity");
		entityTable.setColumns("id", "reference", "type");

		TableHelper personTable = new TableHelper(dbHelper, "person");
		personTable.setColumns("id", "name");

		TableHelper agronomistTable = new TableHelper(dbHelper, "agronomist");
		agronomistTable.setColumns("id", "plants_planted");

		entityTable.insert(1, "p1", "P");
		personTable.insert(1, "Doug");
		agronomistTable.insert(5, 100);

		Person person = context.selectOne(new SelectQuery<>(Person.class, Person.NAME.eq("Doug")));
		Agronomist agronomist = context.selectOne(new SelectQuery<>(Agronomist.class, Agronomist.PLANTS_PLANTED.eq(100)));

		agronomist.setPerson(person);

		context.commitChanges();

		assertEquals(agronomist.getPerson(), person);
		assertEquals(agronomistTable.getInt("person_id"), 1);
	}

	@Test
	public void testPersonOneToOneUpdateFromPerson() throws Exception { //Agronomist has FK to person
		TableHelper entityTable = new TableHelper(dbHelper, "entity");
		entityTable.setColumns("id", "reference", "type");

		TableHelper personTable = new TableHelper(dbHelper, "person");
		personTable.setColumns("id", "name");

		TableHelper agronomistTable = new TableHelper(dbHelper, "agronomist");
		agronomistTable.setColumns("id", "plants_planted");

		entityTable.insert(1, "p1", "P");
		personTable.insert(1, "Doug");
		agronomistTable.insert(5, 100);

		Person person = context.selectOne(new SelectQuery<>(Person.class, Person.NAME.eq("Doug")));
		Agronomist agronomist = context.selectOne(new SelectQuery<>(Agronomist.class, Agronomist.PLANTS_PLANTED.eq(100)));

		person.setAgronomist(agronomist);

		context.commitChanges();

		assertEquals(agronomist.getPerson(), person);
		assertEquals(agronomistTable.getInt("person_id"), 1);
	}

	//TODO Test creating obj that starts with inheritance relationship
	//Test nullifying relationship
	//Test multiple inheritance

}
