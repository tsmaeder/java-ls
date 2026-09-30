package ch.castleridge.fixtures.single;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AppTest {
    @Test
    void hello() {
        assertEquals("hello", new App().hello());
    }
}
