package org.apache.cayenne.testdo.inheritance_vertical_sb.auto;

import java.util.List;

import org.apache.cayenne.CayenneDataObject;
import org.apache.cayenne.exp.Property;
import org.apache.cayenne.testdo.inheritance_vertical_sb.Person;

/**
 * Class _Family was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _Family extends CayenneDataObject {

    private static final long serialVersionUID = 1L; 

    public static final String ID_PK_COLUMN = "id";

    public static final Property<String> LAST_NAME = new Property<String>("lastName");
    public static final Property<List<Person>> PEOPLE = new Property<List<Person>>("people");

    public void setLastName(String lastName) {
        writeProperty("lastName", lastName);
    }
    public String getLastName() {
        return (String)readProperty("lastName");
    }

    public void addToPeople(Person obj) {
        addToManyTarget("people", obj, true);
    }
    public void removeFromPeople(Person obj) {
        removeToManyTarget("people", obj, true);
    }
    @SuppressWarnings("unchecked")
    public List<Person> getPeople() {
        return (List<Person>)readProperty("people");
    }


}
