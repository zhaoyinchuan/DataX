package com.alibaba.datax.plugin.writer.hdfswriter;

import org.apache.commons.lang3.tuple.MutablePair;

import java.util.List;

/**
 * @Description: PartitionResult
 * @Author: zhaoyinchuan
 * @Date: Created in 3:45 PM 2020/4/22
 * @Version: V1.0
 */
public class PartitionResult {

    private String partition;

    private MutablePair<List<Object>, Boolean> transportResult;

    public String getPartition() {
        return partition;
    }

    public void setPartition(final String partition) {
        this.partition = partition;
    }

    public MutablePair<List<Object>, Boolean> getTransportResult() {
        return transportResult;
    }

    public void setTransportResult(
        final MutablePair<List<Object>, Boolean> transportResult) {
        this.transportResult = transportResult;
    }
}
