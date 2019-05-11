package org.apache.cayenne.testdo.compound.auto;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import org.apache.cayenne.CayenneDataObject;
import org.apache.cayenne.exp.property.ListProperty;
import org.apache.cayenne.exp.property.PropertyFactory;
import org.apache.cayenne.exp.property.StringProperty;
import org.apache.cayenne.testdo.compound.CompoundOrderLine;

/**
 * Class _CompoundOrder was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _CompoundOrder extends CayenneDataObject {

    private static final long serialVersionUID = 1L; 

    public static final String ORDER_NUMBER_PK_COLUMN = "order_number";

    public static final StringProperty<String> INFO = PropertyFactory.createString("info", String.class);
    public static final ListProperty<CompoundOrderLine> LINES = PropertyFactory.createList("lines", CompoundOrderLine.class);

    protected String info;

    protected Object lines;

    public void setInfo(String info) {
        beforePropertyWrite("info", this.info, info);
        this.info = info;
    }

    public String getInfo() {
        beforePropertyRead("info");
        return this.info;
    }

    public void addToLines(CompoundOrderLine obj) {
        addToManyTarget("lines", obj, true);
    }

    public void removeFromLines(CompoundOrderLine obj) {
        removeToManyTarget("lines", obj, true);
    }

    @SuppressWarnings("unchecked")
    public List<CompoundOrderLine> getLines() {
        return (List<CompoundOrderLine>)readProperty("lines");
    }

    @Override
    public Object readPropertyDirectly(String propName) {
        if(propName == null) {
            throw new IllegalArgumentException();
        }

        switch(propName) {
            case "info":
                return this.info;
            case "lines":
                return this.lines;
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
            case "info":
                this.info = (String)val;
                break;
            case "lines":
                this.lines = val;
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
        out.writeObject(this.info);
        out.writeObject(this.lines);
    }

    @Override
    protected void readState(ObjectInputStream in) throws IOException, ClassNotFoundException {
        super.readState(in);
        this.info = (String)in.readObject();
        this.lines = in.readObject();
    }

}
