package com.specdriven.regression.contract.v03.assertion;

public record V03AssertionEvaluation(
        boolean passed,
        Object actual,
        Object expected) {
}
