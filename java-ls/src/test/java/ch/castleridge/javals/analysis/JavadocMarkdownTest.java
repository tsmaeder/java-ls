/**
 * Copyright 2026 by Castle Ridge Software GmbH
 *
 * Author: Thomas Mäder, Castle Ridge Software
 *
 */
package ch.castleridge.javals.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavadocMarkdownTest {

    @Test
    void stripsCommentMarkersAndStars() {
        String md = JavadocMarkdown.toMarkdown("""
                /**
                 * Greets the user.
                 */
                """);
        assertEquals("Greets the user.", md);
    }

    @Test
    void rendersCodeAndLinkTags() {
        String md = JavadocMarkdown.toMarkdown("""
                /**
                 * Returns {@code null} or see {@link java.util.List#size()}.
                 */
                """);
        assertTrue(md.contains("`null`"));
        assertTrue(md.contains("size()"));
        assertFalse(md.contains("{@"));
    }

    @Test
    void rendersParamAndReturnTags() {
        String md = JavadocMarkdown.toMarkdown("""
                /**
                 * Greets someone.
                 * @param name the name
                 * @return greeting text
                 */
                """);
        assertTrue(md.contains("Greets someone."));
        assertTrue(md.contains("- **@param** `name` the name"));
        assertTrue(md.contains("- **@return** greeting text"));
    }

    @Test
    void emptyWhenBlank() {
        assertEquals("", JavadocMarkdown.toMarkdown(""));
        assertEquals("", JavadocMarkdown.toMarkdown(null));
    }
}
