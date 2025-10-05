package edu.javeriana.biblioteca;

/**
 * Unit test for simple App.
 */
public class AppTest {
    /**
     * Rigorous Test :-)
     */
    public void shouldAnswerWithTrue() {
        assert true;
    }

    public void testAppExists() {
        App app = new App();
        assert app != null;
    }

    public void testMainMethodExists() {
        try {
            App.main(new String[] {});
            assert true;
        } catch (Exception e) {
            assert false;
        }
    }
}