package com.alibaba.datax.plugin.writer.hivepartitionwriter;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.GlobFilter;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobContext;
import org.apache.hadoop.mapred.RecordWriter;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

public  class HdfsHelper {
    public static final Logger LOG = LoggerFactory.getLogger(HdfsWriter.Job.class);
    public FileSystem fileSystem = null;
    public JobConf conf = null;
    public org.apache.hadoop.conf.Configuration hadoopConf = null;
    public static final String HADOOP_SECURITY_AUTHENTICATION_KEY = "hadoop.security.authentication";
    public static final String HDFS_DEFAULTFS_KEY = "fs.defaultFS";

    // Kerberos
    private Boolean haveKerberos = false;
    private String  kerberosKeytabFilePath;
    private String  kerberosPrincipal;

    public void getFileSystem(String defaultFS, Configuration taskConfig){
        hadoopConf = new org.apache.hadoop.conf.Configuration();

        Configuration hadoopSiteParams = taskConfig.getConfiguration(Key.HADOOP_CONFIG);
        JSONObject hadoopSiteParamsAsJsonObject = JSON.parseObject(taskConfig.getString(Key.HADOOP_CONFIG));
        if (null != hadoopSiteParams) {
            Set<String> paramKeys = hadoopSiteParams.getKeys();
            for (String each : paramKeys) {
                hadoopConf.set(each, hadoopSiteParamsAsJsonObject.getString(each));
            }
        }
        hadoopConf.set(HDFS_DEFAULTFS_KEY, defaultFS);

        //是否有Kerberos认证
        this.haveKerberos = taskConfig.getBool(Key.HAVE_KERBEROS, false);
        if(haveKerberos){
            this.kerberosKeytabFilePath = taskConfig.getString(Key.KERBEROS_KEYTAB_FILE_PATH);
            this.kerberosPrincipal = taskConfig.getString(Key.KERBEROS_PRINCIPAL);
            hadoopConf.set(HADOOP_SECURITY_AUTHENTICATION_KEY, "kerberos");
        }
        this.kerberosAuthentication(this.kerberosPrincipal, this.kerberosKeytabFilePath);
        conf = new JobConf(hadoopConf);
        try {
            fileSystem = FileSystem.get(conf);
        } catch (IOException e) {
            String message = String.format("获取FileSystem时发生网络IO异常,请检查您的网络是否正常!HDFS地址：[%s]",
                    "message:defaultFS =" + defaultFS);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }catch (Exception e) {
            String message = String.format("获取FileSystem失败,请检查HDFS地址是否正确: [%s]",
                    "message:defaultFS =" + defaultFS);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }

        if(null == fileSystem || null == conf){
            String message = String.format("获取FileSystem失败,请检查HDFS地址是否正确: [%s]",
                    "message:defaultFS =" + defaultFS);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, message);
        }
    }

    private void kerberosAuthentication(String kerberosPrincipal, String kerberosKeytabFilePath){
        if(haveKerberos && StringUtils.isNotBlank(this.kerberosPrincipal) && StringUtils.isNotBlank(this.kerberosKeytabFilePath)){
            UserGroupInformation.setConfiguration(this.hadoopConf);
            try {
                UserGroupInformation.loginUserFromKeytab(kerberosPrincipal, kerberosKeytabFilePath);
            } catch (Exception e) {
                String message = String.format("kerberos认证失败,请确定kerberosKeytabFilePath[%s]和kerberosPrincipal[%s]填写正确",
                        kerberosKeytabFilePath, kerberosPrincipal);
                LOG.error(message);
                throw DataXException.asDataXException(HdfsWriterErrorCode.KERBEROS_LOGIN_ERROR, e);
            }
        }
    }

    /**
     *获取指定目录先的文件列表
     * @param dir
     * @return
     * 拿到的是文件全路径，
     * eg：hdfs://10.101.204.12:9000/user/hive/warehouse/writer.db/text/test.textfile
     */
    public String[] hdfsDirList(String dir){
        Path path = new Path(dir);
        String[] files = null;
        try {
            FileStatus[] status = fileSystem.listStatus(path);
            files = new String[status.length];
            for(int i=0;i<status.length;i++){
                files[i] = status[i].getPath().toString();
            }
        } catch (IOException e) {
            String message = String.format("获取目录[%s]文件列表时发生网络IO异常,请检查您的网络是否正常！", dir);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }
        return files;
    }

    /**
     * 获取以fileName__ 开头的文件列表
     * @param dir
     * @param fileName
     * @return
     */
    public Path[] hdfsDirList(String dir,String fileName){
        Path path = new Path(dir);
        Path[] files = null;
        String filterFileName = fileName + "__*";
        try {
            PathFilter pathFilter = new GlobFilter(filterFileName);
            FileStatus[] status = fileSystem.listStatus(path,pathFilter);
            files = new Path[status.length];
            for(int i=0;i<status.length;i++){
                files[i] = status[i].getPath();
            }
        } catch (IOException e) {
            String message = String.format("获取目录[%s]下文件名以[%s]开头的文件列表时发生网络IO异常,请检查您的网络是否正常！",
                    dir,fileName);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }
        return files;
    }

    public boolean isPathexists(String filePath) {
        Path path = new Path(filePath);
        boolean exist = false;
        try {
            exist = fileSystem.exists(path);
        } catch (IOException e) {
            String message = String.format("判断文件路径[%s]是否存在时发生网络IO异常,请检查您的网络是否正常！",
                    "message:filePath =" + filePath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }
        return exist;
    }

    public boolean isPathDir(String filePath) {
        Path path = new Path(filePath);
        boolean isDir = false;
        try {
            isDir = fileSystem.isDirectory(path);
        } catch (IOException e) {
            String message = String.format("判断路径[%s]是否是目录时发生网络IO异常,请检查您的网络是否正常！", filePath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }
        return isDir;
    }

    public void deleteFiles(Path[] paths){
        for(int i=0;i<paths.length;i++){
            LOG.info(String.format("delete file [%s].", paths[i].toString()));
            try {
                fileSystem.delete(paths[i],true);
            } catch (IOException e) {
                String message = String.format("删除文件[%s]时发生IO异常,请检查您的网络是否正常！",
                        paths[i].toString());
                LOG.error(message);
                throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
            }
        }
    }

    public void deleteDir(Path path){
        LOG.info(String.format("start delete tmp dir [%s] .",path.toString()));
        try {
            if(isPathexists(path.toString())) {
                fileSystem.delete(path, true);
            }
        } catch (Exception e) {
            String message = String.format("删除临时目录[%s]时发生IO异常,请检查您的网络是否正常！", path.toString());
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }
        LOG.info(String.format("finish delete tmp dir [%s] .",path.toString()));
    }

    //关闭FileSystem
    public void closeFileSystem(){
        try {
            fileSystem.close();
        } catch (IOException e) {
            String message = String.format("关闭FileSystem时发生IO异常,请检查您的网络是否正常！");
            LOG.error(message);
            throw DataXException.asDataXException(HdfsWriterErrorCode.CONNECT_HDFS_IO_ERROR, e);
        }
    }

    /**
     * 分区写textfile类型文件
     * @param lineReceiver
     * @param config
     * @param fileName 到tablename为止
     * @param taskPluginCollector
     */
    public void textFileStartWritePartition(RecordReceiver lineReceiver, Configuration config, String fileName,
        TaskPluginCollector taskPluginCollector, String writeMode){
        char fieldDelimiter = config.getChar(Key.FIELD_DELIMITER);
        List<Configuration>  columns = config.getListConfiguration(Key.COLUMN);
        String compress = config.getString(Key.COMPRESS,null);

        try {
            Map<String, RecordWriter> writerMap = Maps.newHashMap();
            Record record = null;
            String partitonFileName = null;
            while ((record = lineReceiver.getFromReader()) != null) {
                PartitionResult<Text> partitionResult = transportOneRecordPartition(record, fieldDelimiter, columns, taskPluginCollector);
                String ptDay = partitionResult.getPartition();
                MutablePair<Text, Boolean> transportResult = partitionResult.getTransportResult();
                RecordWriter writer = writerMap.get(ptDay);
                if (writer == null) {
                    String partitionDir = fileName + "/pt_day=" + ptDay;
                    if (writeMode.equalsIgnoreCase("truncate")) {
                        LOG.info(String.format("由于您配置了writeMode truncate, 开始清理 [%s] 目录下的内容", partitionDir));
                        this.deleteDir(new Path(partitionDir));
                    }
                    StringBuilder partitonNameBuilder = new StringBuilder();
                    partitonNameBuilder.append("/").append(ptDay).append("_").append(System.currentTimeMillis());
                    partitonFileName = partitionDir + partitonNameBuilder.toString();
                    // LOG.info(">>>>>>>partitonFileName is :" + partitonFileName);
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmm");
                    String attempt = "attempt_"+dateFormat.format(new Date())+"_0001_m_000000_0";
                    Path outputPath = new Path(partitonFileName);
                    //todo 需要进一步确定TASK_ATTEMPT_ID
                    conf.set(JobContext.TASK_ATTEMPT_ID, attempt);
                    FileOutputFormat outFormat = new TextOutputFormat();
                    outFormat.setOutputPath(conf, outputPath);
                    outFormat.setWorkOutputPath(conf, outputPath);
                    if(null != compress) {
                        Class<? extends CompressionCodec> codecClass = getCompressCodec(compress);
                        if (null != codecClass) {
                            outFormat.setOutputCompressorClass(conf, codecClass);
                        }
                    }
                    // LOG.info(">>>>>>>outputPath is :" + outputPath.toString());
                    writer = outFormat.getRecordWriter(fileSystem, conf, outputPath.toString(), Reporter.NULL);
                    writerMap.put(ptDay, writer);
                }
                if (!transportResult.getRight()) {
                    // LOG.info(">>>>>>>>>record be writted is :" + JSON.toJSONString(transportResult.getLeft()));
                    writer.write(NullWritable.get(),transportResult.getLeft());
                }

            }
            for (RecordWriter closeWriter : writerMap.values()) {
                closeWriter.close(Reporter.NULL);
            }
        } catch (Exception e) {
            String message = String.format("写文件文件[%s]时发生IO异常,请检查您的网络是否正常！", fileName);
            LOG.error(message);
            /*Path path = new Path(fileName);
            deleteDir(path.getParent());*/
            throw DataXException.asDataXException(HdfsWriterErrorCode.Write_FILE_IO_ERROR, e);
        }
    }

    // text file partition overwrite
    public static PartitionResult<Text> transportOneRecordPartition(
        Record record, char fieldDelimiter, List<Configuration> columnsConfiguration, TaskPluginCollector taskPluginCollector) {
        PartitionResult<List<Object>> transportResultList = transportOneRecordPartition(record,columnsConfiguration,taskPluginCollector);
        //保存<转换后的数据,是否是脏数据>
        PartitionResult<Text> partitionResult = new PartitionResult<>();
        MutablePair<Text, Boolean> transportResult = new MutablePair<Text, Boolean>();
        transportResult.setRight(false);
        if(null != transportResultList){
            MutablePair<List<Object>, Boolean> transportResultTmp = transportResultList.getTransportResult();
            Text recordResult = new Text(StringUtils.join(transportResultTmp.getLeft(), fieldDelimiter));
            transportResult.setRight(transportResultTmp.getRight());
            transportResult.setLeft(recordResult);
            // 封装成PartitionResult
            partitionResult.setPartition(transportResultList.getPartition());
            partitionResult.setTransportResult(transportResult);

        }
        return partitionResult;
    }

    public Class<? extends CompressionCodec>  getCompressCodec(String compress){
        Class<? extends CompressionCodec> codecClass = null;
        if(null == compress){
            codecClass = null;
        }else if("GZIP".equalsIgnoreCase(compress)){
            codecClass = org.apache.hadoop.io.compress.GzipCodec.class;
        }else if ("BZIP2".equalsIgnoreCase(compress)) {
            codecClass = org.apache.hadoop.io.compress.BZip2Codec.class;
        }else if("SNAPPY".equalsIgnoreCase(compress)){
            //todo 等需求明确后支持 需要用户安装SnappyCodec
            codecClass = org.apache.hadoop.io.compress.SnappyCodec.class;
            // org.apache.hadoop.hive.ql.io.orc.ZlibCodec.class  not public
            //codecClass = org.apache.hadoop.hive.ql.io.orc.ZlibCodec.class;
        }else {
            throw DataXException.asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE,
                    String.format("目前不支持您配置的 compress 模式 : [%s]", compress));
        }
        return codecClass;
    }

    /**
     * 分区写orcfile类型文件
     * @param lineReceiver
     * @param config
     * @param fileName 到tablename为止
     * @param taskPluginCollector
     */
    public void orcFileStartWritePartition(RecordReceiver lineReceiver, Configuration config, String fileName,
        TaskPluginCollector taskPluginCollector, String writeMode){
        List<Configuration>  columns = config.getListConfiguration(Key.COLUMN);
        String compress = config.getString(Key.COMPRESS, null);
        List<String> columnNames = getColumnNames(columns);
        List<ObjectInspector> columnTypeInspectors = getColumnTypeInspectors(columns);
        StructObjectInspector inspector = (StructObjectInspector)ObjectInspectorFactory
            .getStandardStructObjectInspector(columnNames, columnTypeInspectors);

        OrcSerde orcSerde = new OrcSerde();

        FileOutputFormat outFormat = new OrcOutputFormat();
        if(!"NONE".equalsIgnoreCase(compress) && null != compress ) {
            Class<? extends CompressionCodec> codecClass = getCompressCodec(compress);
            if (null != codecClass) {
                outFormat.setOutputCompressorClass(conf, codecClass);
            }
        }
        try {
            Map<String, RecordWriter> writerMap = Maps.newHashMap();
            Record record = null;
            String partitonFileName = null;
            while ((record = lineReceiver.getFromReader()) != null) {
                PartitionResult<List<Object>> partitionResult = transportOneRecordPartition(record,columns,taskPluginCollector);
                String ptDay = partitionResult.getPartition();
                MutablePair<List<Object>, Boolean> transportResult = partitionResult.getTransportResult();
                RecordWriter writer = writerMap.get(ptDay);
                if (writer == null) {
                    String partitionDir = fileName + "/pt_day=" + ptDay;
                    if (writeMode.equalsIgnoreCase("truncate")) {
                        LOG.info(String.format("由于您配置了writeMode truncate, 开始清理 [%s] 目录下的内容", partitionDir));
                        this.deleteDir(new Path(partitionDir));
                    }
                    StringBuilder partitonNameBuilder = new StringBuilder();
                    partitonNameBuilder.append("/").append(ptDay).append("_").append(System.currentTimeMillis());
                    partitonFileName = partitionDir + partitonNameBuilder.toString();
                    writer = outFormat.getRecordWriter(fileSystem, conf, partitonFileName, Reporter.NULL);
                    writerMap.put(ptDay, writer);
                }
                // LOG.info(">>>>>>>outputPath is :" + partitonFileName);
                if (!transportResult.getRight()) {
                    writer.write(NullWritable.get(), orcSerde.serialize(transportResult.getLeft(), inspector));
                }

            }
            for (RecordWriter closeWriter : writerMap.values()) {
                closeWriter.close(Reporter.NULL);
            }
        } catch (Exception e) {
            String message = String.format("写文件文件[%s]时发生IO异常,请检查您的网络是否正常！", fileName);
            LOG.error(message);
            /*Path path = new Path(fileName);
            deleteDir(path.getParent());*/
            throw DataXException.asDataXException(HdfsWriterErrorCode.Write_FILE_IO_ERROR, e);
        }
    }

    public List<String> getColumnNames(List<Configuration> columns){
        List<String> columnNames = Lists.newArrayList();
        for (Configuration eachColumnConf : columns) {
            columnNames.add(eachColumnConf.getString(Key.NAME));
        }
        return columnNames;
    }

    /**
     * 根据writer配置的字段类型，构建inspector
     * @param columns
     * @return
     */
    public List<ObjectInspector>  getColumnTypeInspectors(List<Configuration> columns){
        List<ObjectInspector>  columnTypeInspectors = Lists.newArrayList();
        for (Configuration eachColumnConf : columns) {
            SupportHiveDataType columnType = SupportHiveDataType.valueOf(eachColumnConf.getString(Key.TYPE).toUpperCase());
            ObjectInspector objectInspector = null;
            switch (columnType) {
                case TINYINT:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(Byte.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case SMALLINT:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(Short.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case INT:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(Integer.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case BIGINT:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(Long.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case FLOAT:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(Float.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case DOUBLE:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(Double.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case TIMESTAMP:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(java.sql.Timestamp.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case DATE:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(java.sql.Date.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case STRING:
                case VARCHAR:
                case CHAR:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(String.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                case BOOLEAN:
                    objectInspector = ObjectInspectorFactory.getReflectionObjectInspector(Boolean.class, ObjectInspectorFactory.ObjectInspectorOptions.JAVA);
                    break;
                default:
                    throw DataXException
                            .asDataXException(
                                    HdfsWriterErrorCode.ILLEGAL_VALUE,
                                    String.format(
                                            "您的配置文件中的列配置信息有误. 因为DataX 不支持数据库写入这种字段类型. 字段名:[%s], 字段类型:[%d]. 请修改表中该字段的类型或者不同步该字段.",
                                            eachColumnConf.getString(Key.NAME),
                                            eachColumnConf.getString(Key.TYPE)));
            }

            columnTypeInspectors.add(objectInspector);
        }
        return columnTypeInspectors;
    }

    // orc file partition overwrite
    public static PartitionResult<List<Object>> transportOneRecordPartition(
        Record record,List<Configuration> columnsConfiguration,
        TaskPluginCollector taskPluginCollector){

        PartitionResult<List<Object>> partitionResult = null;
        String partition = null;
        MutablePair<List<Object>, Boolean> transportResult = new MutablePair<List<Object>, Boolean>();
        transportResult.setRight(false);
        List<Object> recordList = Lists.newArrayList();
        int recordLength = record.getColumnNumber();
        if (0 != recordLength) {
            Column column;
            for (int i = 0; i < recordLength; i++) {
                column = record.getColumn(i);
                //todo as method
                if (null != column.getRawData()) {
                    String rowData = column.getRawData().toString();
                    if (i == 0) {
                        partition = column.asString(); continue;
                    }
                    SupportHiveDataType columnType = SupportHiveDataType.valueOf(
                        columnsConfiguration.get(i).getString(Key.TYPE).toUpperCase());
                    //根据writer端类型配置做类型转换
                    try {
                        switch (columnType) {
                            case TINYINT:
                                recordList.add(Byte.valueOf(rowData));
                                break;
                            case SMALLINT:
                                recordList.add(Short.valueOf(rowData));
                                break;
                            case INT:
                                recordList.add(Integer.valueOf(rowData));
                                break;
                            case BIGINT:
                                recordList.add(column.asLong());
                                break;
                            case FLOAT:
                                recordList.add(Float.valueOf(rowData));
                                break;
                            case DOUBLE:
                                recordList.add(column.asDouble());
                                break;
                            case STRING:
                            case VARCHAR:
                            case CHAR:
                                recordList.add(column.asString());
                                break;
                            case BOOLEAN:
                                recordList.add(column.asBoolean());
                                break;
                            case DATE:
                                recordList.add(new java.sql.Date(column.asDate().getTime()));
                                break;
                            case TIMESTAMP:
                                recordList.add(new java.sql.Timestamp(column.asDate().getTime()));
                                break;
                            default:
                                throw DataXException
                                    .asDataXException(
                                        HdfsWriterErrorCode.ILLEGAL_VALUE,
                                        String.format(
                                            "您的配置文件中的列配置信息有误. 因为DataX 不支持数据库写入这种字段类型. 字段名:[%s], 字段类型:[%d]. 请修改表中该字段的类型或者不同步该字段.",
                                            columnsConfiguration.get(i).getString(Key.NAME),
                                            columnsConfiguration.get(i).getString(Key.TYPE)));
                        }
                    } catch (Exception e) {
                        // warn: 此处认为脏数据
                        String message = String.format(
                            "字段类型转换错误：你目标字段为[%s]类型，实际字段值为[%s].",
                            columnsConfiguration.get(i).getString(Key.TYPE), column.getRawData().toString());
                        taskPluginCollector.collectDirtyRecord(record, message);
                        transportResult.setRight(true);
                        break;
                    }
                }else {
                    // warn: it's all ok if nullFormat is null
                    recordList.add(null);
                }
            }
        }
        transportResult.setLeft(recordList);
        if (partition != null) {
            partitionResult = new PartitionResult<List<Object>>();
            partitionResult.setPartition(partition);
            partitionResult.setTransportResult(transportResult);
        } else {
            throw DataXException
                .asDataXException(HdfsWriterErrorCode.ILLEGAL_VALUE, "未获取分区值");
        }
        return partitionResult;
    }
}
