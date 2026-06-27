package com.specdriven.regression.provider;

import java.io.IOException;

interface MessagingTransport {

    MessagingTransportResult publish(MessagingTransportRequest request) throws IOException, InterruptedException;
}
