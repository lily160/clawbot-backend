package com.github.wechat.cloudbot.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class StringsTest {

    @ParameterizedTest
    @CsvSource({
            "hello, 10, hello",
            "hello world, 5, hello...",
            "abc, 3, abc",
            "abcdef, 3, abc...",
    })
    void testTruncate(String input, int max, String expected) {
        assertEquals(expected, Strings.truncate(input, max));
    }

    @Test
    void testTruncateNull() {
        assertEquals("", Strings.truncate(null, 10));
    }

    @Test
    void testSummarize() {
        String longStr = "a".repeat(100);
        assertEquals(83, Strings.summarize(longStr).length());  // 80 + "..."
        assertTrue(Strings.summarize(longStr).endsWith("..."));
    }

    @Test
    void testSummarizeShort() {
        assertEquals("hello", Strings.summarize("hello"));
    }

    @Test
    void testFirstTextBasic() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("name", "Alice");
        root.put("title", "Engineer");

        assertEquals("Alice", Strings.firstText(root, "name", "title"));
        assertEquals("Engineer", Strings.firstText(root, "unknown", "title"));
    }

    @Test
    void testFirstTextBlank() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("name", "");
        assertEquals("", Strings.firstText(root, "name"));
    }

    @Test
    void testFirstTextNotFound() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        assertEquals("", Strings.firstText(root, "nonexistent"));
    }

    @Test
    void testFirstTextNullRoot() {
        assertEquals("", Strings.firstText(null, "key"));
    }

    @Test
    void testFirstTextNullKeys() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        assertEquals("", Strings.firstText(root, (String[]) null));
    }

    @Test
    void testTrimTrailingSlash() {
        assertEquals("https://api.example.com", Strings.trimTrailingSlash("https://api.example.com/", "x"));
        assertEquals("https://fallback", Strings.trimTrailingSlash("  ", "https://fallback"));
    }

    @Test
    void testTextAndFirstNonBlank() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("a", "  hi  ");
        assertEquals("hi", Strings.text(root, "a"));
        assertEquals("", Strings.text(root, "missing"));
        assertEquals("x", Strings.firstNonBlank("", "  x  ", "y"));
    }

    @Test
    void testSummarizeOneLine() {
        assertEquals("a b", Strings.summarizeOneLine("a\nb"));
        assertTrue(Strings.summarizeOneLine("x".repeat(100)).endsWith("..."));
    }
}
