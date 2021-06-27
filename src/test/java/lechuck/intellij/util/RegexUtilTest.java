package lechuck.intellij.util;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

import java.util.List;

public class RegexUtilTest {

    @Test
    public void testSplitBySpacePreservingQuotes() {
        assertEquals(List.of(), RegexUtil.splitBySpacePreservingQuotes(""));
        assertEquals(List.of("a", "b"), RegexUtil.splitBySpacePreservingQuotes("a b"));
        assertEquals(List.of("a", "a b"), RegexUtil.splitBySpacePreservingQuotes("a  \"a b\""));
    }
}
