package org.apache.cayenne.testdo.meaningful_pk.auto;

import java.util.List;

import org.apache.cayenne.CayenneDataObject;
import org.apache.cayenne.exp.Property;
import org.apache.cayenne.testdo.meaningful_pk.MeaningfulPKDep;

/**
 * Class _MeaningfulPKTest1 was generated by Cayenne.
 * It is probably a good idea to avoid changing this class manually,
 * since it may be overwritten next time code is regenerated.
 * If you need to make any customizations, please use subclass.
 */
public abstract class _MeaningfulPKTest1 extends CayenneDataObject {

    private static final long serialVersionUID = 1L; 

    @Deprecated
    public static final String DESCR_PROPERTY = "descr";
    @Deprecated
    public static final String PK_ATTRIBUTE_PROPERTY = "pkAttribute";
    @Deprecated
    public static final String MEANINGFUL_PKDEP_ARRAY_PROPERTY = "meaningfulPKDepArray";

    public static final String PK_ATTRIBUTE_PK_COLUMN = "PK_ATTRIBUTE";

    public static final Property<String> DESCR = new Property<String>("descr");
    public static final Property<Integer> PK_ATTRIBUTE = new Property<Integer>("pkAttribute");
    public static final Property<List<MeaningfulPKDep>> MEANINGFUL_PKDEP_ARRAY = new Property<List<MeaningfulPKDep>>("meaningfulPKDepArray");

    public void setDescr(String descr) {
        writeProperty("descr", descr);
    }
    public String getDescr() {
        return (String)readProperty("descr");
    }

    public void setPkAttribute(Integer pkAttribute) {
        writeProperty("pkAttribute", pkAttribute);
    }
    public Integer getPkAttribute() {
        return (Integer)readProperty("pkAttribute");
    }

    public void addToMeaningfulPKDepArray(MeaningfulPKDep obj) {
        addToManyTarget("meaningfulPKDepArray", obj, true);
    }
    public void removeFromMeaningfulPKDepArray(MeaningfulPKDep obj) {
        removeToManyTarget("meaningfulPKDepArray", obj, true);
    }
    @SuppressWarnings("unchecked")
    public List<MeaningfulPKDep> getMeaningfulPKDepArray() {
        return (List<MeaningfulPKDep>)readProperty("meaningfulPKDepArray");
    }


}
