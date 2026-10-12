package com.example;

public class Main {
    public static void main(String[] args) {
        System.out.println("IDE run e2e: " + String.join(" ", args) + " in " + System.getenv("IDE_RUN_E2E"));
    }
}
