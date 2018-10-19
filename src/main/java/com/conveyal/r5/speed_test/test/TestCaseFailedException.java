package com.conveyal.r5.speed_test.test;

/**
 * The purpose of this exception is to signal that a testcase failed.
 */
public class TestCaseFailedException extends RuntimeException {
    TestCaseFailedException() {
        super("Test assert errors");
    }
}