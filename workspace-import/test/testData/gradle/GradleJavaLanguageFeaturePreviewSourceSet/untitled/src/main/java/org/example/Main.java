package org.example;

import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;

public class Main {

    static void main() {
        Callable<String> task1 = () -> "Hello World";
        Callable<Integer> task2 = () -> 42;

        try (var scope = StructuredTaskScope.open()) {
            StructuredTaskScope.Subtask<String> subtask1 = scope.fork(task1);
            StructuredTaskScope.Subtask<Integer> subtask2 = scope.fork(task2);
            scope.join();

            System.out.println("subtask1: " + subtask1.get());
            System.out.println("subtask2: " + subtask2.get());
        } catch (InterruptedException e) {
            System.out.println("InterruptedException");
        }
    }
}