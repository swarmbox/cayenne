package org.apache.cayenne.testdo.inheritance_vertical.auto;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import org.apache.cayenne.exp.property.EntityProperty;
import org.apache.cayenne.exp.property.ListProperty;
import org.apache.cayenne.exp.property.PropertyFactory;
import org.apache.cayenne.exp.property.StringProperty;
import org.apache.cayenne.testdo.inheritance_vertical.IvAbstract;
import org.apache.cayenne.testdo.inheritance_vertical.IvConcrete;

/**
 * Class _IvConcrete was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _IvConcrete extends IvAbstract {

    private static final long serialVersionUID = 1L;

    public static final String ID_PK_COLUMN = "ID";

    public static final StringProperty<String> NAME = PropertyFactory.createString("name", String.class);
    public static final ListProperty<IvConcrete> CHILDREN = PropertyFactory.createList("children", IvConcrete.class);
    public static final EntityProperty<IvConcrete> PARENT = PropertyFactory.createEntity("parent", IvConcrete.class);
    public static final EntityProperty<IvAbstract> RELATED_ABSTRACT = PropertyFactory.createEntity("relatedAbstract", IvAbstract.class);

    protected String name;

    protected Object children;
    protected Object parent;
    protected Object relatedAbstract;

    public void setName(String name) {
        beforePropertyWrite("name", this.name, name);
        this.name = name;
    }

    public String getName() {
        beforePropertyRead("name");
        return this.name;
    }

    public void addToChildren(IvConcrete obj) {
        addToManyTarget("children", obj, true);
    }

    public void removeFromChildren(IvConcrete obj) {
        removeToManyTarget("children", obj, true);
    }

    @SuppressWarnings("unchecked")
    public List<IvConcrete> getChildren() {
        return (List<IvConcrete>)readProperty("children");
    }

    public void setParent(IvConcrete parent) {
        setToOneTarget("parent", parent, true);
    }

    public IvConcrete getParent() {
        return (IvConcrete)readProperty("parent");
    }

    public void setRelatedAbstract(IvAbstract relatedAbstract) {
        setToOneTarget("relatedAbstract", relatedAbstract, true);
    }

    public IvAbstract getRelatedAbstract() {
        return (IvAbstract)readProperty("relatedAbstract");
    }

    @Override
    public Object readPropertyDirectly(String propName) {
        if(propName == null) {
            throw new IllegalArgumentException();
        }

        switch(propName) {
            case "name":
                return this.name;
            case "children":
                return this.children;
            case "parent":
                return this.parent;
            case "relatedAbstract":
                return this.relatedAbstract;
            default:
                return super.readPropertyDirectly(propName);
        }
    }

    @Override
    public void writePropertyDirectly(String propName, Object val) {
        if(propName == null) {
            throw new IllegalArgumentException();
        }

        switch (propName) {
            case "name":
                this.name = (String)val;
                break;
            case "children":
                this.children = val;
                break;
            case "parent":
                this.parent = val;
                break;
            case "relatedAbstract":
                this.relatedAbstract = val;
                break;
            default:
                super.writePropertyDirectly(propName, val);
        }
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        writeSerialized(out);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        readSerialized(in);
    }

    @Override
    protected void writeState(ObjectOutputStream out) throws IOException {
        super.writeState(out);
        out.writeObject(this.name);
        out.writeObject(this.children);
        out.writeObject(this.parent);
        out.writeObject(this.relatedAbstract);
    }

    @Override
    protected void readState(ObjectInputStream in) throws IOException, ClassNotFoundException {
        super.readState(in);
        this.name = (String)in.readObject();
        this.children = in.readObject();
        this.parent = in.readObject();
        this.relatedAbstract = in.readObject();
    }

}
