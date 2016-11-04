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
		assertEquals(5, personTable.getInt("family_id"));
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

		assertEquals(5, personTable.getInt("drivers_license_id"));
		assertEquals(person.getDriversLicense(), dl);
		assertEquals(dl.getPerson(), person);
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
		assertEquals(5, personTable.getInt("drivers_license_id"));
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
		assertEquals(1, agronomistTable.getInt("person_id"));
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
		assertEquals(1, agronomistTable.getInt("person_id"));
	}

    @Test
    public void testPersonOneToOneUpdateBirthCertificate() throws Exception {
        setupDefaultPerson();

        TableHelper personTable = new TableHelper(dbHelper, "iv_person");

        TableHelper documentTable = new TableHelper(dbHelper, "iv_document");
        documentTable.setColumns("id", "type");
        
        TableHelper birthCertificateTable = new TableHelper(dbHelper, "iv_birth_certificate");
        birthCertificateTable.setColumns("id");

        documentTable.insert(3, "B");
        birthCertificateTable.insert(3);

        IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
        IvBirthCertificate birthCertificate = context.selectOne(new SelectQuery<>(IvBirthCertificate.class));

        person.setBirthCertificate(birthCertificate);

        context.commitChanges();

        assertEquals(person.getBirthCertificate(), birthCertificate);
        assertEquals(3, personTable.getInt("birth_certificate_id"));
    }

    @Test
    public void testPersonOneToManyUpdateReceipts() throws Exception {
        setupDefaultPerson();

        TableHelper documentTable = new TableHelper(dbHelper, "iv_document");
        documentTable.setColumns("id", "type");

        TableHelper receiptTable = new TableHelper(dbHelper, "iv_receipt");
        receiptTable.setColumns("id");
        
        documentTable.insert(3, "R");
        receiptTable.insert(3);

        IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
        IvReceipt receipt = context.selectOne(new SelectQuery<>(IvReceipt.class));

        person.addToReceipts(receipt);

        context.commitChanges();

        assertEquals(receipt.getPerson(), person);
        assertEquals(1, receiptTable.getInt("person_id"));
    }

    @Test
    public void testPersonOneToOneInsertDriversLicense() throws Exception {
        TableHelper entityTable = new TableHelper(dbHelper, "iv_entity");
        TableHelper personTable = new TableHelper(dbHelper, "iv_person");
        TableHelper driversLicenseTable = new TableHelper(dbHelper, "iv_drivers_license");

        IvPerson person = context.newObject(IvPerson.class);
        IvDriversLicense dl = context.newObject(IvDriversLicense.class);

        person.setDriversLicense(dl);

        context.commitChanges();

        assertEquals(1, entityTable.getRowCount());
        assertEquals(1, personTable.getRowCount());
        assertEquals(1, driversLicenseTable.getRowCount());
        assertNotEquals(0, personTable.getInt("drivers_license_id"));
    }
    
    @Test
    public void testPersonOneToOneInsertAgronomist() throws Exception {
        TableHelper entityTable = new TableHelper(dbHelper, "iv_entity");
        TableHelper personTable = new TableHelper(dbHelper, "iv_person");
        TableHelper agronomistTable = new TableHelper(dbHelper, "iv_agronomist");

        IvPerson person = context.newObject(IvPerson.class);
        IvAgronomist agronomist = context.newObject(IvAgronomist.class);

        person.setAgronomist(agronomist);

        context.commitChanges();

        assertEquals(1, entityTable.getRowCount());
        assertEquals(1, personTable.getRowCount());
        assertEquals(1, agronomistTable.getRowCount());
        assertNotEquals(0, agronomistTable.getInt("person_id"));
    }
    
    @Test
    public void testPersonOneToManyInsertFamily() throws Exception {
        TableHelper entityTable = new TableHelper(dbHelper, "iv_entity");
        TableHelper personTable = new TableHelper(dbHelper, "iv_person");
        TableHelper familyTable = new TableHelper(dbHelper, "iv_family");

        IvPerson person = context.newObject(IvPerson.class);
        IvFamily family = context.newObject(IvFamily.class);

        person.setFamily(family);

        context.commitChanges();

        assertEquals(1, entityTable.getRowCount());
        assertEquals(1, personTable.getRowCount());
        assertEquals(1, familyTable.getRowCount());
        assertNotEquals(0, personTable.getInt("family_id"));
    }
    
    @Test
    public void testPersonOneToOneInsertBirthCertificate() throws Exception {
        TableHelper entityTable = new TableHelper(dbHelper, "iv_entity");
        TableHelper personTable = new TableHelper(dbHelper, "iv_person");
        TableHelper birthCertificateTable = new TableHelper(dbHelper, "iv_birth_certificate");

        IvPerson person = context.newObject(IvPerson.class);
        IvBirthCertificate bc = context.newObject(IvBirthCertificate.class);

        person.setBirthCertificate(bc);

        context.commitChanges();

        assertEquals(1, entityTable.getRowCount());
        assertEquals(1, personTable.getRowCount());
        assertEquals(1, birthCertificateTable.getRowCount());
        assertNotEquals(0, personTable.getInt("birth_certificate_id"));
    }
    
    @Test
    public void testPersonOneToManyInsertReceipt() throws Exception {
        TableHelper entityTable = new TableHelper(dbHelper, "iv_entity");
        TableHelper personTable = new TableHelper(dbHelper, "iv_person");
        TableHelper receiptTable = new TableHelper(dbHelper, "iv_receipt");

        IvPerson person = context.newObject(IvPerson.class);
        IvReceipt receipt = context.newObject(IvReceipt.class);

        person.addToReceipts(receipt);

        context.commitChanges();

        assertEquals(1, entityTable.getRowCount());
        assertEquals(1, personTable.getRowCount());
        assertEquals(1, receiptTable.getRowCount());
        assertNotEquals(0, receiptTable.getInt("person_id"));
    }


    // Multiple-level vertical inheritance tests

    @Test
    public void testPersonOneToManyUpdateSalesOrder() throws Exception {
        setupDefaultPerson();

        TableHelper documentTable = new TableHelper(dbHelper, "iv_document");
        documentTable.setColumns("id", "type");

        TableHelper orderTable = new TableHelper(dbHelper, "iv_order");
        orderTable.setColumns("id", "order_type");

        TableHelper salesOrderTable = new TableHelper(dbHelper, "iv_sales_order");
        salesOrderTable.setColumns("id");

        documentTable.insert(3, "O");
        orderTable.insert(3, "S");
        salesOrderTable.insert(3);

        IvPerson person = context.selectOne(new SelectQuery<>(IvPerson.class));
        IvSalesOrder so = context.selectOne(new SelectQuery<>(IvSalesOrder.class));

        person.addToSalesOrders(so);

        context.commitChanges();

        assertEquals(so.getPerson(), person);
        assertEquals(1, salesOrderTable.getInt("person_id"));
    }

    @Test
    public void testReceiptOneToOneUpdateSalesOrder() throws Exception {
        TableHelper documentTable = new TableHelper(dbHelper, "iv_document");
        documentTable.setColumns("id", "type");

        TableHelper receiptTable = new TableHelper(dbHelper, "iv_receipt");
        receiptTable.setColumns("id");

        TableHelper orderTable = new TableHelper(dbHelper, "iv_order");
        orderTable.setColumns("id", "order_type");

        TableHelper salesOrderTable = new TableHelper(dbHelper, "iv_sales_order");
        salesOrderTable.setColumns("id");

        documentTable.insert(2, "R");
        receiptTable.insert(2);
        documentTable.insert(3, "O");
        orderTable.insert(3, "S");
        salesOrderTable.insert(3);

        IvReceipt receipt = context.selectOne(new SelectQuery<>(IvReceipt.class));
        IvSalesOrder so = context.selectOne(new SelectQuery<>(IvSalesOrder.class));

        receipt.setSalesOrder(so);

        context.commitChanges();

        assertEquals(so.getReceipt(), receipt);
        assertEquals(3, receiptTable.getInt("sales_order_id"));
    }


    @Test
    public void testPersonOneToManyInsertSalesOrder() throws Exception {
        TableHelper entityTable = new TableHelper(dbHelper, "iv_entity");
        TableHelper personTable = new TableHelper(dbHelper, "iv_person");
        TableHelper documentTable = new TableHelper(dbHelper, "iv_document");
        TableHelper orderTable = new TableHelper(dbHelper, "iv_order");
        TableHelper salesOrderTable = new TableHelper(dbHelper, "iv_sales_order");

        IvPerson person = context.newObject(IvPerson.class);

        //FIX SO has no (document) type on creation. See BaseContext.injectInitialValue
        IvSalesOrder so = context.newObject(IvSalesOrder.class);


        person.addToSalesOrders(so);

        context.commitChanges();

        assertEquals(1, entityTable.getRowCount());
        assertEquals(1, personTable.getRowCount());
        assertEquals(1, documentTable.getRowCount());
        assertEquals(1, orderTable.getRowCount());
        assertEquals(1, salesOrderTable.getRowCount());
        assertNotEquals(0, salesOrderTable.getInt("person_id"));
    }

    @Test
    public void testReceiptOneToOneInsertSalesOrder() throws Exception {
        TableHelper documentTable = new TableHelper(dbHelper, "iv_document");
        TableHelper receiptTable = new TableHelper(dbHelper, "iv_receipt");
        TableHelper orderTable = new TableHelper(dbHelper, "iv_order");
        TableHelper salesOrderTable = new TableHelper(dbHelper, "iv_sales_order");

        IvReceipt receipt = context.newObject(IvReceipt.class);

        //FIX SO has no (document) type on creation. See BaseContext.injectInitialValue
        IvSalesOrder so = context.newObject(IvSalesOrder.class);


        receipt.setSalesOrder(so);

        context.commitChanges();

        assertEquals(2, documentTable.getRowCount());
        assertEquals(1, receiptTable.getRowCount());
        assertEquals(1, orderTable.getRowCount());
        assertEquals(1, salesOrderTable.getRowCount());
        assertNotEquals(0, receiptTable.getInt("sales_order_id"));
    }



    @Test
    public void testAgronomistOneToManyUpdateOrder() throws Exception {
        TableHelper agronomistTable = new TableHelper(dbHelper, "iv_agronomist");
        agronomistTable.setColumns("id");

        TableHelper documentTable = new TableHelper(dbHelper, "iv_document");
        documentTable.setColumns("id", "type");

        TableHelper orderTable = new TableHelper(dbHelper, "iv_order");
        orderTable.setColumns("id", "order_type");

        TableHelper salesOrderTable = new TableHelper(dbHelper, "iv_sales_order");
        salesOrderTable.setColumns("id");

        agronomistTable.insert(8);
        documentTable.insert(3, "O");
        orderTable.insert(3, "S");
        salesOrderTable.insert(3);

        IvAgronomist agronomist = context.selectOne(new SelectQuery<>(IvAgronomist.class));
        IvSalesOrder so = context.selectOne(new SelectQuery<>(IvSalesOrder.class));

        agronomist.addToOrders(so);

        context.commitChanges();

        assertEquals(so.getAgronomist(), agronomist);
        assertEquals(8, orderTable.getInt("agronomist_id"));
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
		assertEquals(1, emailTable.getInt("entity_id"));
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
	//Test multiple level inheritance - before this, need to settle any 3+ dbRel flattened rel issues

}
