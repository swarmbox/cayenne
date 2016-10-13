package org.apache.cayenne.testdo.inheritance_vertical.auto;

import org.apache.cayenne.exp.Property;
import org.apache.cayenne.testdo.inheritance_vertical.IvShape;

/**
 * Class _IvConcrete was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _IvCircle extends IvShape {

    private static final long serialVersionUID = 1L; 

    public static final String ID_PK_COLUMN = "ID";

    public static final Property<Integer> RADIAS = new Property<Integer>("radias");

    public void setRadias(Integer radias) {
        writeProperty("radias", radias);
    }
    public Integer getRadias() {
        return (Integer)readProperty("radias");
    }

}
