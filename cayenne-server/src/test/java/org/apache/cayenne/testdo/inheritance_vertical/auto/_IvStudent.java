package org.apache.cayenne.testdo.inheritance_vertical.auto;

import org.apache.cayenne.CayenneDataObject;
import org.apache.cayenne.exp.Property;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvShape;

import java.util.List;

/**
 * Class _IvStudent was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _IvStudent extends CayenneDataObject {

    private static final long serialVersionUID = 1L; 

    public static final String ID_PK_COLUMN = "ID";

    public static final Property<String> NAME = new Property<String>("name");
    public static final Property<IvShape> FAVORITE_SHAPE = new Property<IvShape>("favoriteShape");

    public void setName(String name) {
        writeProperty("name", name);
    }
    public String getName() {
        return (String)readProperty("name");
    }

    public void setFavoriteShape(IvShape favoriteShape) {
        writeProperty("favoriteShape", favoriteShape);
    }
    public IvShape getFavoriteShape() {
        return (IvShape)readProperty("favoriteShape");
    }

}
