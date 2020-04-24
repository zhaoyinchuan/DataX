package com.alibaba.datax.plugin.writer.hivepartitionwriter;

import org.apache.commons.lang3.tuple.MutablePair;

/**
 * @Description: PartitionResult
 * @Author: zhaoyinchuan
 * @Date: Created in 3:45 PM 2020/4/22
 * @Version: V1.0
 */
public class PartitionResult<T> {

    private String partition;

    private MutablePair<T, Boolean> transportResult;

    public String getPartition() {
        return partition;
    }

    public void setPartition(final String partition) {
        this.partition = partition;
    }

    public MutablePair<T, Boolean> getTransportResult() {
        return transportResult;
    }

    public void setTransportResult(
        final MutablePair<T, Boolean> transportResult) {
        this.transportResult = transportResult;
    }
}
