package org.example;

import java.util.concurrent.ThreadLocalRandom;

public class Sub21Main {

    public String HelloJava21() {
        int seed = ThreadLocalRandom.current().nextInt(0, 100);
        String result = switch (seed) {
            case 11 -> "foo";
            case 22 -> "bar";
            case 33 -> "buzz";
            case 44 -> "brr";
            default -> "unglück :(";
        };
        return """
                Hello
                from Java 21:
                
                """ + result;
    }
}