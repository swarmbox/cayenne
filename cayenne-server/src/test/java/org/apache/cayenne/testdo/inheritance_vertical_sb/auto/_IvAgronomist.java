package org.apache.cayenne.testdo.inheritance_vertical_sb.auto;

import java.util.List;

import org.apache.cayenne.CayenneDataObject;
import org.apache.cayenne.exp.Property;
import org.apache.cayenne.testdo.inheritance_vertical_sb.IvOrder;
import org.apache.cayenne.testdo.inheritance_vertical_sb.IvPerson;

/**
 * Class _IvAgronomist was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _IvAgronomist extends CayenneDataObject {

    private static final long serialVersionUID = 1L; 

    public static final String ID_PK_COLUMN = "id";

    public static final Property<Integer> PLANTS_PLANTED = new Property<Integer>("plantsPlanted");
    public static final Property<List<IvOrder>> ORDERS = new Property<List<IvOrder>>("orders");
    public static final Property<IvPerson> PERSON = new Property<IvPerson>("person");

    public void setPlantsPlanted(int plantsPlanted) {
        writeProperty("plantsPlanted", plantsPlanted);
    }
    public int getPlantsPlanted() {
        Object value = readProperty("plantsPlanted");
        return (value != null) ? (Integer) value : 0;
    }

    public void addToOrders(IvOrder obj) {
        addToManyTarget("orders", obj, true);
    }
    public void removeFromOrders(IvOrder obj) {
        removeToManyTarget("orders", obj, true);
    }
    @SuppressWarnings("unchecked")
    public List<IvOrder> getOrders() {
        return (List<IvOrder>)readProperty("orders");
    }


    public void setPerson(IvPerson person) {
        setToOneTarget("person", person, true);
    }

    public IvPerson getPerson() {
        return (IvPerson)readProperty("person");
    }


}
