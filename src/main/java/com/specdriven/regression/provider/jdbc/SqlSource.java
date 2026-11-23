package com.specdriven.regression.provider.jdbc;

import com.specdriven.regression.provider.SuiteAssetReader;
import java.io.IOException;
import java.nio.file.Path;

/** Reads SQL assets from the checked-out suite or RP package. */
public final class SqlSource {
    private SqlSource() { }

    public static String read(Path root, String reference, String outsideRootMessage) throws IOException {
        return SuiteAssetReader.read(root, reference, outsideRootMessage);
    }
}
