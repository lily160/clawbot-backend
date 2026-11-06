package com.github.wechat.cloudbot.modules.fun.service;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class JokeServiceTest {

    private final JokeService jokeService = new JokeService();

    @RepeatedTest(20)
    void testRandomJoke() {
        String joke = jokeService.randomJoke();
        assertNotNull(joke);
        assertFalse(joke.isBlank());
    }

    @RepeatedTest(20)
    void testRandomRiddle() {
        String riddle = jokeService.randomRiddle();
        assertNotNull(riddle);
        assertFalse(riddle.isBlank());
        assertTrue(riddle.contains("答案"));
    }

    @RepeatedTest(20)
    void testRandomQuote() {
        String quote = jokeService.randomQuote();
        assertNotNull(quote);
        assertFalse(quote.isBlank());
    }

    @Test
    void testRandomDefault() {
        String result = jokeService.random(null);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void testRandomBlank() {
        String result = jokeService.random(" ");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @ParameterizedTest
    @CsvSource({
            "joke",
            "JOKE",
            "Joke",
            "riddle",
            "RIDDLE",
            "quote",
            "QUOTE",
    })
    void testRandomByType(String type) {
        String result = jokeService.random(type);
        assertNotNull(result);
        assertFalse(result.isBlank());
    }

    @Test
    void testRandomUnknownType() {
        String result = jokeService.random("unknown");
        assertNotNull(result);
    }
}
