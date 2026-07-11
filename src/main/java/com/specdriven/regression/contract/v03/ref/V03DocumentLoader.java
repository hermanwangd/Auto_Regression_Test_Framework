package com.specdriven.regression.contract.v03.ref;

import java.nio.file.Path;

@FunctionalInterface
public interface V03DocumentLoader {
    Object load(Path path);
}
