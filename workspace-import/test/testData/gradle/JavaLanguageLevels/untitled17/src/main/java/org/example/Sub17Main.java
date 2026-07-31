package org.example;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Sub17Main {

    public String helloJava17() {
        return "Hello from: " + Stream.of(new World(), new LSP(), new Idea())
                .map(Hello::whoami)
                .collect(Collectors.joining(", "));
    }

    private sealed interface Hello permits World, LSP, Idea {
        String whoami();
    }

    private static final class World implements Hello {
        @Override
        public String whoami() {
            return "world";
        }
    }

    private static final class LSP implements Hello {
        @Override
        public String whoami() {
            return "LSP";
        }
    }

    private static final class Idea implements Hello {
        @Override
        public String whoami() {
            return "IDEA";
        }
    }
}