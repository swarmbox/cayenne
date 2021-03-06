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

package org.apache.cayenne.gen;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StringUtilsTest {

    protected StringUtils stringUtils;

    @Before
    public void setUp() throws Exception {
        stringUtils = new StringUtils();
    }

    @After
    public void tearDown() throws Exception {
        stringUtils = null;
    }

    @Test
    public void testPluralize() throws Exception {
        assertEquals("Words", stringUtils.pluralize("Word"));
        assertEquals("Statuses", stringUtils.pluralize("Status"));
        assertEquals("Indexes", stringUtils.pluralize("Index"));
        assertEquals("Factories", stringUtils.pluralize("Factory"));
        assertEquals("", stringUtils.pluralize(""));
        assertEquals(null, stringUtils.pluralize(null));
    }


    @Test
    public void testCapitalizedAsConstant1() throws Exception {
        String expected = "LAST_NAME";
        assertEquals(expected, stringUtils.capitalizedAsConstant("LastName"));
    }

    @Test
    public void testCapitalizedAsConstant2() throws Exception {
        String expected = "A_CLASS";
        assertEquals(expected, stringUtils.capitalizedAsConstant("aClass"));
    }

    @Test
    public void testCapitalizedAsConstant3() throws Exception {
        String expected = "VAR_A";
        assertEquals(expected, stringUtils.capitalizedAsConstant("varA"));
    }

    @Test
    public void testCapitalizedAsConstant4() throws Exception {
        String expected = "LAST_NAME";
        assertEquals(expected, stringUtils.capitalizedAsConstant("LAST_NAME"));
    }

    @Test
    public void testCapitalizedAsConstant5() throws Exception {
        String expected = "ABC_A";
        assertEquals(expected, stringUtils.capitalizedAsConstant("abc_A"));
    }

    @Test
    public void testCapitalizedAsConstant6() throws Exception {
        String expected = "A123";
        assertEquals(expected, stringUtils.capitalizedAsConstant("a123"));
    }

    @Test
    public void testCapitalizedAsConstant7() throws Exception {
        String expected = "AB_CDEF";
        assertEquals(expected, stringUtils.capitalizedAsConstant("abCDEF"));
    }

    @Test
    public void testCapitalizedAsConstant8() throws Exception {
        String expected = "AB_CE";
        assertEquals(expected, stringUtils.capitalizedAsConstant("abCe"));
    }

}
