package org.example;

import java.util.Arrays;
import java.util.List;

public class Sub11Main {

    public String helloJava11() {
        List<String> sampleList = Arrays.asList("Java", "Kotlin");
        String[] sampleArray = sampleList.toArray(String[]::new);
        return Arrays.deepToString(sampleArray);
    }
}