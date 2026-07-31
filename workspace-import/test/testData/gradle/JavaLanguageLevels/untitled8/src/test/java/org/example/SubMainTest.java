package org.example;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubMainTest {

    @Test
    public void testHello() {
        SubMain subMain = new SubMain();
        assertEquals(getExpectedValue(), subMain.helloJava8());
        assertTrue(List.of("foo", "bar", "buzz").contains(helloSwitch()));
    }

    private String getExpectedValue() {
        List<String> sampleList = Arrays.asList("hello", "world");
        String[] sampleArray = sampleList.toArray(String[]::new);
        return String.join(" ", sampleArray);
    }

    public String helloSwitch() {
        return switch (ThreadLocalRandom.current().nextInt(0, 3)) {
            case 1 -> "foo";
            case 2 -> "bar";
            case 3 -> "buzz";
            default -> throw new IllegalStateException();
        };
    }
}