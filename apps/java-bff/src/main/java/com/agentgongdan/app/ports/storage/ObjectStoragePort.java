package com.agentgongdan.app.ports.storage;

import java.io.InputStream;

public interface ObjectStoragePort {

    void put(String objectKey, InputStream stream, long size, String contentType);

    String presignedGetUrl(String objectKey, int expirySeconds);

    void remove(String objectKey);
}
