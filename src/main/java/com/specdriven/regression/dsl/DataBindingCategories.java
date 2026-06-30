package com.specdriven.regression.dsl;

import java.util.Set;

public final class DataBindingCategories {

    public static final Set<String> ALLOWED = Set.of(
            "input_data",
            "setup_data",
            "cleanup_data",
            "expect_data");
    public static final String OWNER_ACTION =
            "Use data_binding.input_data, setup_data, cleanup_data, or expect_data only.";

    private DataBindingCategories() {
    }

    public static boolean allowed(String category) {
        return ALLOWED.contains(category);
    }
}
