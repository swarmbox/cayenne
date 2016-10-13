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
import org.apache.cayenne.testdo.inheritance_vertical.*;
import org.apache.cayenne.unit.di.server.CayenneProjects;
import org.apache.cayenne.unit.di.server.ServerCase;
import org.apache.cayenne.unit.di.server.UseServerRuntime;
import org.junit.Test;

import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.awt.Color.red;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static sun.management.snmp.jvminstr.JvmThreadInstanceEntryImpl.ThreadStateMap.Byte1.other;

@UseServerRuntime(CayenneProjects.INHERITANCE_VERTICAL_PROJECT)
public class VerticalInheritanceIT extends ServerCase {

	@Inject
	protected ObjectContext context;

	@Inject
	protected DBHelper dbHelper;

    @Test
	public void testInsert_Root() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR");

		assertEquals(0, ivRootTable.getRowCount());

		IvRoot root = context.newObject(IvRoot.class);
		root.setName("XyZ");
		root.getObjectContext().commitChanges();

		assertEquals(1, ivRootTable.getRowCount());

		Object[] rootData = ivRootTable.select();
		assertEquals(3, rootData.length);
		assertTrue(rootData[0] instanceof Number);
		assertTrue(((Number) rootData[0]).intValue() > 0);
		assertEquals("XyZ", rootData[1]);
		assertNull(rootData[2]);
	}

    @Test
	public void testInsert_Sub1() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR");

		TableHelper ivSub1Table = new TableHelper(dbHelper, "IV_SUB1");
		ivSub1Table.setColumns("ID", "SUB1_NAME");

		IvSub1 sub1 = context.newObject(IvSub1.class);
		sub1.setName("XyZX");
		sub1.getObjectContext().commitChanges();

		assertEquals(1, ivRootTable.getRowCount());
		assertEquals(1, ivSub1Table.getRowCount());

		Object[] data = ivRootTable.select();
		assertEquals(3, data.length);
		assertTrue(data[0] instanceof Number);
		assertTrue(((Number) data[0]).intValue() > 0);
		assertEquals("XyZX", data[1]);
		assertEquals("IvSub1", data[2]);

		Object[] subdata = ivSub1Table.select();
		assertEquals(2, subdata.length);
		assertEquals(data[0], subdata[0]);
		assertNull(subdata[1]);

		ivSub1Table.deleteAll();
		ivRootTable.deleteAll();

		IvSub1 sub11 = context.newObject(IvSub1.class);
		sub11.setName("XyZXY");
		sub11.setSub1Name("BdE2");
		sub11.getObjectContext().commitChanges();

		data = ivRootTable.select();
		assertEquals(3, data.length);
		assertTrue(data[0] instanceof Number);
		assertTrue(((Number) data[0]).intValue() > 0);
		assertEquals("XyZXY", data[1]);
		assertEquals("IvSub1", data[2]);

		subdata = ivSub1Table.select();
		assertEquals(2, subdata.length);
		assertEquals(data[0], subdata[0]);
		assertEquals("BdE2", subdata[1]);
	}

    @Test
	public void testInsert_Sub2() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR");

		TableHelper ivSub2Table = new TableHelper(dbHelper, "IV_SUB2");
		ivSub2Table.setColumns("ID", "SUB2_NAME", "SUB2_ATTR");

		IvSub2 sub2 = context.newObject(IvSub2.class);
		sub2.setName("XyZX");
		sub2.getObjectContext().commitChanges();

		assertEquals(1, ivRootTable.getRowCount());
		assertEquals(1, ivSub2Table.getRowCount());

		Object[] data = ivRootTable.select();
		assertEquals(3, data.length);
		assertTrue(data[0] instanceof Number);
		assertTrue(((Number) data[0]).intValue() > 0);
		assertEquals("XyZX", data[1]);
		assertEquals("IvSub2", data[2]);

		Object[] subdata = ivSub2Table.select();
		assertEquals(3, subdata.length);
		assertEquals(data[0], subdata[0]);
		assertNull(subdata[1]);
		assertNull(subdata[2]);

		ivSub2Table.deleteAll();
		ivRootTable.deleteAll();

		IvSub2 sub21 = context.newObject(IvSub2.class);
		sub21.setName("XyZXY");
		sub21.setSub2Name("BdE2");
		sub21.setSub2Attr("aTtR");
		sub21.getObjectContext().commitChanges();

		data = ivRootTable.select();
		assertEquals(3, data.length);
		assertTrue(data[0] instanceof Number);
		assertTrue(((Number) data[0]).intValue() > 0);
		assertEquals("XyZXY", data[1]);
		assertEquals("IvSub2", data[2]);

		subdata = ivSub2Table.select();
		assertEquals(3, subdata.length);
		assertEquals(data[0], subdata[0]);
		assertEquals("BdE2", subdata[1]);
		assertEquals("aTtR", subdata[2]);

		sub21.setSub2Attr("BUuT");
		sub21.getObjectContext().commitChanges();

		subdata = ivSub2Table.select();
		assertEquals(3, subdata.length);
		assertEquals(data[0], subdata[0]);
		assertEquals("BdE2", subdata[1]);
		assertEquals("BUuT", subdata[2]);

		sub21.getObjectContext().deleteObjects(sub21);
		sub21.getObjectContext().commitChanges();

		assertEquals(0, ivRootTable.getRowCount());
		assertEquals(0, ivSub2Table.getRowCount());
	}

    @Test
	public void testInsert_Sub1Sub1() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR");

		TableHelper ivSub1Table = new TableHelper(dbHelper, "IV_SUB1");
		ivSub1Table.setColumns("ID", "SUB1_NAME");

		TableHelper ivSub1Sub1Table = new TableHelper(dbHelper, "IV_SUB1_SUB1");
		ivSub1Sub1Table.setColumns("ID", "SUB1_SUB1_NAME");

		IvSub1Sub1 sub1Sub1 = context.newObject(IvSub1Sub1.class);
		sub1Sub1.setName("XyZN");
		sub1Sub1.setSub1Name("mDA");
		sub1Sub1.setSub1Sub1Name("3DQa");
		sub1Sub1.getObjectContext().commitChanges();

		assertEquals(1, ivRootTable.getRowCount());
		assertEquals(1, ivSub1Table.getRowCount());
		assertEquals(1, ivSub1Sub1Table.getRowCount());

		Object[] data = ivRootTable.select();
		assertEquals(3, data.length);
		assertTrue(data[0] instanceof Number);
		assertTrue(((Number) data[0]).intValue() > 0);
		assertEquals("XyZN", data[1]);
		assertEquals("IvSub1Sub1", data[2]);

		Object[] subdata = ivSub1Table.select();
		assertEquals(2, subdata.length);
		assertEquals(data[0], subdata[0]);
		assertEquals("mDA", subdata[1]);

		Object[] subsubdata = ivSub1Sub1Table.select();
		assertEquals(2, subsubdata.length);
		assertEquals(data[0], subsubdata[0]);
		assertEquals("3DQa", subsubdata[1]);
	}

    @Test
	public void testSelectQuery_SuperSub() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR").setColumnTypes(
				Types.INTEGER, Types.VARCHAR, Types.VARCHAR);

		TableHelper ivSub1Table = new TableHelper(dbHelper, "IV_SUB1");
		ivSub1Table.setColumns("ID", "SUB1_NAME");

		// insert
		ivRootTable.insert(1, "xROOT", null);
		ivRootTable.insert(2, "xSUB1_ROOT", "IvSub1");
		ivSub1Table.insert(2, "xSUB1");

		SelectQuery query = new SelectQuery(IvRoot.class);
		List<IvRoot> results = context.performQuery(query);

		assertEquals(2, results.size());

		// since we don't have ordering, need to analyze results in an order
		// agnostic
		// fashion
		Map<String, IvRoot> resultTypes = new HashMap<>();

		for (IvRoot result : results) {
			resultTypes.put(result.getClass().getName(), result);
		}

		assertEquals(2, resultTypes.size());

		IvRoot root = resultTypes.get(IvRoot.class.getName());
		assertNotNull(root);
		assertEquals("xROOT", root.getName());
		assertNull(root.getDiscriminator());

		IvSub1 sub1 = (IvSub1) resultTypes.get(IvSub1.class.getName());
		assertNotNull(sub1);
		assertEquals("xSUB1_ROOT", sub1.getName());
		assertEquals("IvSub1", sub1.getDiscriminator());
	}

    @Test
	public void testSelectQuery_DeepAndWide() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR").setColumnTypes(
				Types.INTEGER, Types.VARCHAR, Types.VARCHAR);

		TableHelper ivSub1Table = new TableHelper(dbHelper, "IV_SUB1");
		ivSub1Table.setColumns("ID", "SUB1_NAME");

		TableHelper ivSub2Table = new TableHelper(dbHelper, "IV_SUB2");
		ivSub2Table.setColumns("ID", "SUB2_NAME");

		TableHelper ivSub1Sub1Table = new TableHelper(dbHelper, "IV_SUB1_SUB1");
		ivSub1Sub1Table.setColumns("ID", "SUB1_SUB1_NAME");

		// insert
		ivRootTable.insert(1, "xROOT", null);

		ivRootTable.insert(2, "xSUB1_ROOT", "IvSub1");
		ivSub1Table.insert(2, "xSUB1");

		ivRootTable.insert(3, "xSUB1_SUB1_ROOT", "IvSub1Sub1");
		ivSub1Table.insert(3, "xSUB1_SUB1_SUBROOT");
		ivSub1Sub1Table.insert(3, "xSUB1_SUB1");

		ivRootTable.insert(4, "xROOT_SUB2", "IvSub2");
		ivSub2Table.insert(4, "xSUB2");

		SelectQuery query = new SelectQuery(IvRoot.class);
		List<IvRoot> results = context.performQuery(query);

		assertEquals(4, results.size());

		// since we don't have ordering, need to analyze results in an order
		// agnostic
		// fashion
		Map<String, IvRoot> resultTypes = new HashMap<>();

		for (IvRoot result : results) {
			resultTypes.put(result.getClass().getName(), result);
		}

		assertEquals(4, resultTypes.size());

		IvRoot root = resultTypes.get(IvRoot.class.getName());
		assertNotNull(root);
		assertEquals("xROOT", root.getName());
		assertNull(root.getDiscriminator());

		IvSub1 sub1 = (IvSub1) resultTypes.get(IvSub1.class.getName());
		assertNotNull(sub1);
		assertEquals("xSUB1_ROOT", sub1.getName());
		assertEquals("IvSub1", sub1.getDiscriminator());

		IvSub1Sub1 sub1Sub1 = (IvSub1Sub1) resultTypes.get(IvSub1Sub1.class
				.getName());
		assertNotNull(sub1Sub1);
		assertEquals("xSUB1_SUB1_ROOT", sub1Sub1.getName());
		assertEquals("IvSub1Sub1", sub1Sub1.getDiscriminator());
		assertEquals("xSUB1_SUB1_SUBROOT", sub1Sub1.getSub1Name());
		assertEquals("xSUB1_SUB1", sub1Sub1.getSub1Sub1Name());

		IvSub2 sub2 = (IvSub2) resultTypes.get(IvSub2.class.getName());
		assertNotNull(sub2);
		assertEquals("xROOT_SUB2", sub2.getName());
		assertEquals("IvSub2", sub2.getDiscriminator());
		assertEquals("xSUB2", sub2.getSub2Name());
	}

    @Test
	public void testSelectQuery_MiddleLeaf() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR").setColumnTypes(
				Types.INTEGER, Types.VARCHAR, Types.VARCHAR);

		TableHelper ivSub1Table = new TableHelper(dbHelper, "IV_SUB1");
		ivSub1Table.setColumns("ID", "SUB1_NAME");

		TableHelper ivSub2Table = new TableHelper(dbHelper, "IV_SUB2");
		ivSub2Table.setColumns("ID", "SUB2_NAME");

		TableHelper ivSub1Sub1Table = new TableHelper(dbHelper, "IV_SUB1_SUB1");
		ivSub1Sub1Table.setColumns("ID", "SUB1_SUB1_NAME");

		// insert
		ivRootTable.insert(1, "xROOT", null);

		ivRootTable.insert(2, "xSUB1_ROOT", "IvSub1");
		ivSub1Table.insert(2, "xSUB1");

		ivRootTable.insert(3, "xSUB1_SUB1_ROOT", "IvSub1Sub1");
		ivSub1Table.insert(3, "xSUB1_SUB1_SUBROOT");
		ivSub1Sub1Table.insert(3, "xSUB1_SUB1");

		ivRootTable.insert(4, "xROOT_SUB2", "IvSub2");
		ivSub2Table.insert(4, "xSUB2");

		SelectQuery query = new SelectQuery(IvSub1.class);
		List<IvRoot> results = context.performQuery(query);

		assertEquals(2, results.size());

		// since we don't have ordering, need to analyze results in an order
		// agnostic
		// fashion
		Map<String, IvRoot> resultTypes = new HashMap<>();

		for (IvRoot result : results) {
			resultTypes.put(result.getClass().getName(), result);
		}

		assertEquals(2, resultTypes.size());

		IvSub1 sub1 = (IvSub1) resultTypes.get(IvSub1.class.getName());
		assertNotNull(sub1);
		assertEquals("xSUB1_ROOT", sub1.getName());
		assertEquals("IvSub1", sub1.getDiscriminator());

		IvSub1Sub1 sub1Sub1 = (IvSub1Sub1) resultTypes.get(IvSub1Sub1.class
				.getName());
		assertNotNull(sub1Sub1);
		assertEquals("xSUB1_SUB1_ROOT", sub1Sub1.getName());
		assertEquals("IvSub1Sub1", sub1Sub1.getDiscriminator());
		assertEquals("xSUB1_SUB1_SUBROOT", sub1Sub1.getSub1Name());
		assertEquals("xSUB1_SUB1", sub1Sub1.getSub1Sub1Name());
	}

    @Test
	public void testDelete_Mix() throws Exception {

		TableHelper ivRootTable = new TableHelper(dbHelper, "IV_ROOT");
		ivRootTable.setColumns("ID", "NAME", "DISCRIMINATOR").setColumnTypes(
				Types.INTEGER, Types.VARCHAR, Types.VARCHAR);

		TableHelper ivSub1Table = new TableHelper(dbHelper, "IV_SUB1");
		ivSub1Table.setColumns("ID", "SUB1_NAME");

		TableHelper ivSub2Table = new TableHelper(dbHelper, "IV_SUB2");
		ivSub2Table.setColumns("ID", "SUB2_NAME");

		TableHelper ivSub1Sub1Table = new TableHelper(dbHelper, "IV_SUB1_SUB1");
		ivSub1Sub1Table.setColumns("ID", "SUB1_SUB1_NAME");

		// insert
		ivRootTable.insert(1, "xROOT", null);

		ivRootTable.insert(2, "xSUB1_ROOT", "IvSub1");
		ivSub1Table.insert(2, "xSUB1");

		ivRootTable.insert(3, "xSUB1_SUB1_ROOT", "IvSub1Sub1");
		ivSub1Table.insert(3, "xSUB1_SUB1_SUBROOT");
		ivSub1Sub1Table.insert(3, "xSUB1_SUB1");

		ivRootTable.insert(4, "xROOT_SUB2", "IvSub2");
		ivSub2Table.insert(4, "xSUB2");

		SelectQuery query = new SelectQuery(IvRoot.class);

		List<IvRoot> results = context.performQuery(query);

		assertEquals(4, results.size());
		Map<String, IvRoot> resultTypes = new HashMap<>();

		for (IvRoot result : results) {
			resultTypes.put(result.getClass().getName(), result);
		}

		assertEquals(4, resultTypes.size());

		IvRoot root = resultTypes.get(IvRoot.class.getName());
		context.deleteObjects(root);

		IvSub1 sub1 = (IvSub1) resultTypes.get(IvSub1.class.getName());
		context.deleteObjects(sub1);

		context.commitChanges();

		assertEquals(2, ivRootTable.getRowCount());
		assertEquals(1, ivSub1Table.getRowCount());
		assertEquals(1, ivSub1Sub1Table.getRowCount());
		assertEquals(1, ivSub2Table.getRowCount());

		results = context.performQuery(query);
		assertEquals(2, results.size());
	}

    @Test
	public void testSelectQuery_AttributeOverrides() throws Exception {

		TableHelper iv1RootTable = new TableHelper(dbHelper, "IV1_ROOT");
		iv1RootTable.setColumns("ID", "NAME", "DISCRIMINATOR").setColumnTypes(
				Types.INTEGER, Types.VARCHAR, Types.VARCHAR);

		TableHelper iv1Sub1Table = new TableHelper(dbHelper, "IV1_SUB1");
		iv1Sub1Table.setColumns("ID", "SUB1_NAME");

		// insert
		iv1RootTable.insert(1, "xROOT", null);
		iv1RootTable.insert(2, "xSUB1_ROOT", "Iv1Sub1");
		iv1Sub1Table.insert(2, "xSUB1");

		SelectQuery query = new SelectQuery(Iv1Root.class);
		List<Iv1Root> results = context.performQuery(query);

		assertEquals(2, results.size());

		// since we don't have ordering, need to analyze results in an order
		// agnostic
		// fashion
		Map<String, Iv1Root> resultTypes = new HashMap<>();

		for (Iv1Root result : results) {
			resultTypes.put(result.getClass().getName(), result);
		}

		assertEquals(2, resultTypes.size());

		Iv1Root root = resultTypes.get(Iv1Root.class.getName());
		assertNotNull(root);
		assertEquals("xROOT", root.getName());
		assertNull(root.getDiscriminator());

		Iv1Sub1 sub1 = (Iv1Sub1) resultTypes.get(Iv1Sub1.class.getName());
		assertNotNull(sub1);
		assertEquals("xSUB1", sub1.getName());
	}

    @Test
	public void testInsertWithRelationship() throws SQLException {
		TableHelper xTable = new TableHelper(dbHelper, "IV2_X");
		TableHelper rootTable = new TableHelper(dbHelper, "IV2_ROOT");
		TableHelper sub1Table = new TableHelper(dbHelper, "IV2_SUB1");

		assertEquals(0, xTable.getRowCount());
		assertEquals(0, rootTable.getRowCount());
		assertEquals(0, sub1Table.getRowCount());

		Iv2Sub1 root = context.newObject(Iv2Sub1.class);
		Iv2X x = context.newObject(Iv2X.class);
		root.setX(x);

		context.commitChanges();

		assertEquals(1, xTable.getRowCount());
		assertEquals(1, rootTable.getRowCount());
		assertEquals(1, sub1Table.getRowCount());
	}

	@Test
	public void testUpdateWithRelationship() {
		IvConcrete parent1 = context.newObject(IvConcrete.class);
		parent1.setName("Parent1");
		context.commitChanges();

		IvConcrete parent2 = context.newObject(IvConcrete.class);
		parent2.setName("Parent2");
		context.commitChanges();

		IvConcrete child = context.newObject(IvConcrete.class);
		child.setName("Child");
		child.setParent(parent1);
		context.commitChanges();

		child.setParent(parent2);
		context.commitChanges();

		assertEquals(parent2, child.getParent());

		// Manually delete child to prevent a foreign key constraint failure while cleaning MySQL db
		context.deleteObject(child);
		context.commitChanges();
	}

	@Test
	public void testInsertWithAttributeAndRelationship() {
		IvOther other = context.newObject(IvOther.class);
		other.setName("other");

		IvImpl impl = context.newObject(IvImpl.class);
		impl.setName("Impl 1");
		impl.setAttr1("attr1");
		impl.setOther(other);

		context.commitChanges();
	}

	@Test
	public void testSupportPolymorphicRelationshipsInsertAndAccess() {
		//setup data
		IvOther other = context.newObject(IvOther.class);
		other.setName("Other");

		IvStudent jack = context.newObject(IvStudent.class);
		jack.setName("Jack");

		IvStudent jill = context.newObject(IvStudent.class);
		jill.setName("Jill");

		IvColor red = context.newObject(IvColor.class);
		red.setName("Red");

		IvColor blue = context.newObject(IvColor.class);
		blue.setName("Blue");

		context.commitChanges();

		// now relate them

		IvSquare littleRedSquare = context.newObject(IvSquare.class);
		littleRedSquare.setName("Little Red Square");
		littleRedSquare.setColor(red);
		littleRedSquare.setSideLength(3);
		littleRedSquare.setOther(other);

		IvSquare bigRedSquare = context.newObject(IvSquare.class);
		bigRedSquare.setName("Big Red Square");
		bigRedSquare.setColor(red);
		bigRedSquare.setSideLength(300);
		bigRedSquare.setOther(other);

		IvSquare blueSquare = context.newObject(IvSquare.class);
		blueSquare.setName("Blue Square");
		blueSquare.setColor(blue);
		blueSquare.setSideLength(8);
		blueSquare.setOther(other);

		IvCircle redCircle = context.newObject(IvCircle.class);
		redCircle.setName("Red Circle");
		redCircle.setColor(red);
		redCircle.setRadius(4);

		IvCircle blueCircle = context.newObject(IvCircle.class);
		blueCircle.setName("Blue Circle");
		blueCircle.setColor(blue);
		blueCircle.setRadius(9);

		jack.setFavoriteShape(littleRedSquare);
		jill.setFavoriteShape(blueCircle);

		context.commitChanges();

		// access the polymorphic relationships

		int redSquaresCount = 0;
		int redCirclesCount = 0;

		for(IvShape redShape : red.getShapes()) { // <-- Polymorphic ToMany
			if (redShape instanceof IvSquare) {
				redSquaresCount++;
			} else if (redShape instanceof IvCircle) {
				redCirclesCount++;
			}
		}

		assertEquals(2, redSquaresCount);
		assertEquals(1, redCirclesCount);

		assertEquals(littleRedSquare, jack.getFavoriteShape()); // <-- Polymorphic ToOne

	}

	@Test
	public void testSupportPolymorphicRelationshipsSelectAndAccess() throws Exception {
		// Manual db inserts before we fetch them via Select
		TableHelper otherTable = new TableHelper(dbHelper, "IV_OTHER");
		otherTable.setColumns("ID", "NAME");

		TableHelper colorTable = new TableHelper(dbHelper, "IV_COLOR");
		colorTable.setColumns("ID", "NAME");

		TableHelper shapeTable = new TableHelper(dbHelper, "IV_SHAPE");
		shapeTable.setColumns("ID", "TYPE", "NAME", "COLOR_ID");

		TableHelper circleTable = new TableHelper(dbHelper, "IV_CIRCLE");
		circleTable.setColumns("ID", "RADIUS");

		TableHelper squareTable = new TableHelper(dbHelper, "IV_SQUARE");
		squareTable.setColumns("ID", "SIDE_LENGTH", "OTHER_ID");

		TableHelper studentTable = new TableHelper(dbHelper, "IV_STUDENT");
		studentTable.setColumns("ID", "NAME", "FAVORITE_SHAPE_ID");

		// insert
		otherTable.insert(1, "Other");

		colorTable.insert(1, "Red");
		colorTable.insert(2, "Blue");

		shapeTable.insert(1, "S", "Little Red Square", 1);
		shapeTable.insert(2, "S", "Big Red Square", 1);
		shapeTable.insert(3, "S", "Blue Square", 2);
		shapeTable.insert(4, "C", "Red Circle", 1);
		shapeTable.insert(5, "C", "Blue Circle", 2);

		squareTable.insert(1, 3, 1);
		squareTable.insert(2, 300, 1);
		squareTable.insert(3, 8, 1);

		circleTable.insert(4, 4);
		circleTable.insert(5, 9);

		studentTable.insert(1, "Jack", 1);
		studentTable.insert(2, "Jill", 5);

		// Select and access the polymorphic relationships
		IvColor red = context.selectOne(new SelectQuery<>(IvColor.class, IvColor.NAME.eq("Red")));
		IvStudent jack = context.selectOne(new SelectQuery<>(IvStudent.class, IvStudent.NAME.eq("Jack")));

		int redSquaresCount = 0;
		int redCirclesCount = 0;

		for(IvShape redShape : red.getShapes()) { // <-- Polymorphic ToMany
			if (redShape instanceof IvSquare) {
				redSquaresCount++;
			} else if (redShape instanceof IvCircle) {
				redCirclesCount++;
			}
		}

		assertEquals(2, redSquaresCount);
		assertEquals(1, redCirclesCount);

		assertEquals("Little Red Square", jack.getFavoriteShape().getName()); // <-- Polymorphic ToOne
		assertEquals(IvSquare.class, jack.getFavoriteShape().getClass());

	}

}
