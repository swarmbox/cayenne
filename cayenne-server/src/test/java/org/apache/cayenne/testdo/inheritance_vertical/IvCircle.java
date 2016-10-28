package org.apache.cayenne.testdo.inheritance_vertical;

import org.apache.cayenne.testdo.inheritance_vertical.auto._IvCircle;

public class IvCircle extends _IvCircle {

    private static final long serialVersionUID = 1L;

    public void addToOthers(IvOther obj) {
        addToManyTarget("others", obj, true);
    }
    public void removeFromOthers(IvOther obj) {
        removeToManyTarget("others", obj, true);
    }

}
