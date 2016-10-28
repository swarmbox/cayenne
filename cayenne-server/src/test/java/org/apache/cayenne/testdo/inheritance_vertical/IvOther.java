package org.apache.cayenne.testdo.inheritance_vertical;

import org.apache.cayenne.testdo.inheritance_vertical.auto._IvOther;

public class IvOther extends _IvOther {

    private static final long serialVersionUID = 1L;

    public void addToCircles(IvCircle obj) {
        addToManyTarget("circles", obj, true);
    }
    public void removeFromCircles(IvCircle obj) {
        removeToManyTarget("circles", obj, true);
    }

}
