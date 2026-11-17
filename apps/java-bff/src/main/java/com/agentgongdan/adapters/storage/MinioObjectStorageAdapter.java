package com.agentgongdan.adapters.storage;

import com.agentgongdan.app.ports.storage.ObjectStoragePort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Repository
public class MinioObjectStorageAdapter implements ObjectStoragePort {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioObjectStorageAdapter(MinioClient minioClient,
                                     @Value("${MINIO_BUCKET:agent-gongdan}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, InputStream stream, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, size, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_STORAGE_ERROR);
        }
    }

    @Override
    public String presignedGetUrl(String objectKey, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Http.Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_STORAGE_ERROR);
        }
    }

    @Override
    public void remove(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_STORAGE_ERROR);
        }
    }
}
