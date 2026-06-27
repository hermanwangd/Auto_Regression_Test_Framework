package com.specdriven.regression.provider;

import java.io.IOException;

interface GrpcClientInvoker {

    GrpcClientResult invoke(GrpcClientRequest request) throws IOException, InterruptedException;
}
