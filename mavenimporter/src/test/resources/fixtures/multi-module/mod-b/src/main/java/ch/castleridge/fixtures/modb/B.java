package ch.castleridge.fixtures.modb;

import ch.castleridge.fixtures.moda.A;

public class B {
    public String useA() {
        return new A().name();
    }
}
