package org.apache.cayenne.testdo.inheritance_vertical.auto;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.apache.cayenne.PersistentObject;
import org.apache.cayenne.exp.property.EntityProperty;
import org.apache.cayenne.exp.property.NumericIdProperty;
import org.apache.cayenne.exp.property.PropertyFactory;
import org.apache.cayenne.exp.property.SelfProperty;
import org.apache.cayenne.exp.property.StringProperty;
import org.apache.cayenne.testdo.inheritance_vertical.IvAbstract;
import org.apache.cayenne.testdo.inheritance_vertical.IvConcrete;

/**
 * Class _IvAbstract was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _IvAbstract extends PersistentObject {

    private static final long serialVersionUID = 1L;

    public static final SelfProperty<IvAbstract> SELF = PropertyFactory.createSelf(IvAbstract.class);

    public static final NumericIdProperty<Integer> ID_PK_PROPERTY = PropertyFactory.createNumericId("ID", "IvAbstract", Integer.class);
    public static final String ID_PK_COLUMN = "ID";

    public static final StringProperty<String> TYPE = PropertyFactory.createString("type", String.class);
    public static final EntityProperty<IvConcrete> RELATED_CONCRETE = PropertyFactory.createEntity("relatedConcrete", IvConcrete.class);

    protected String type;

    protected Object relatedConcrete;

    public void setType(String type) {
        beforePropertyWrite("type", this.type, type);
        this.type = type;
    }

    public String getType() {
        beforePropertyRead("type");
        return this.type;
    }

    public void setRelatedConcrete(IvConcrete relatedConcrete) {
        setToOneTarget("relatedConcrete", relatedConcrete, true);
    }

    public IvConcrete getRelatedConcrete() {
        return (IvConcrete)readProperty("relatedConcrete");
    }

    @Override
    public Object readPropertyDirectly(String propName) {
        if(propName == null) {
            throw new IllegalArgumentException();
        }

        switch(propName) {
            case "type":
                return this.type;
            case "relatedConcrete":
                return this.relatedConcrete;
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
            case "type":
                this.type = (String)val;
                break;
            case "relatedConcrete":
                this.relatedConcrete = val;
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
        out.writeObject(this.type);
        out.writeObject(this.relatedConcrete);
    }

    @Override
    protected void readState(ObjectInputStream in) throws IOException, ClassNotFoundException {
        super.readState(in);
        this.type = (String)in.readObject();
        this.relatedConcrete = in.readObject();
    }

}