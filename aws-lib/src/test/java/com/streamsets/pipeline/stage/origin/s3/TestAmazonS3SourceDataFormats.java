/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.s3;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.Headers;
import com.amazonaws.services.s3.iterable.S3Objects;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.config.CsvHeader;
import com.streamsets.pipeline.config.CsvMode;
import com.streamsets.pipeline.config.CsvRecordType;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.config.JsonMode;
import com.streamsets.pipeline.config.LogMode;
import com.streamsets.pipeline.config.OriginAvroSchemaSource;
import com.streamsets.pipeline.config.PostProcessingOptions;
import com.streamsets.pipeline.lib.io.fileref.FileRefUtil;
import com.streamsets.pipeline.sdk.SourceRunner;
import com.streamsets.pipeline.sdk.StageRunner;
import com.streamsets.pipeline.stage.common.AmazonS3TestSuite;
import com.streamsets.pipeline.stage.common.TestUtil;
import com.streamsets.pipeline.stage.lib.aws.AWSConfig;
import com.streamsets.pipeline.stage.lib.aws.AWSRegions;
import com.streamsets.pipeline.stage.lib.aws.ProxyConfig;
import com.streamsets.pipeline.stage.origin.lib.BasicConfig;
import com.streamsets.pipeline.stage.origin.lib.DataParserFormatConfig;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.internal.util.reflection.Whitebox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TestAmazonS3SourceDataFormats extends AmazonS3TestSuite{

  private static AmazonS3 s3client;
  private static final String BUCKET_NAME = "testamazonssourcedataformats";

  @BeforeClass
  public static void setUpClass() throws Exception {
    setupS3();
    populateFakes3();
  }

  @AfterClass
  public static void tearDownClass() {
    teardownS3();
  }

  private static void populateFakes3() throws IOException, InterruptedException {
    BasicAWSCredentials credentials = new BasicAWSCredentials("foo", "bar");
    s3client = AmazonS3ClientBuilder
        .standard()
        .withCredentials(new AWSStaticCredentialsProvider(credentials))
        .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration("http://localhost:" + port, null))
        .withPathStyleAccessEnabled(true)
        .withChunkedEncodingDisabled(true)
        .build();

    TestUtil.createBucket(s3client, BUCKET_NAME);

    //write files each under myBucket

    //delimited
    try (InputStream in = Resources.getResource("sample_csv.csv").openStream()) {
      PutObjectRequest putObjectRequest = new PutObjectRequest(BUCKET_NAME, "sample_csv.csv", in, new ObjectMetadata());
      s3client.putObject(putObjectRequest);
    }

    //sdc
    try (InputStream in = Resources.getResource("sample_sdc.sdc").openStream()) {
      // This file is > 8kb which is the mark limit for BufferedInputStream created by openStream()
      byte[] data = ByteStreams.toByteArray(in);
      ByteArrayInputStream bIn = new ByteArrayInputStream(data);
      ObjectMetadata metadata = new ObjectMetadata();
      metadata.setContentLength(data.length);
      PutObjectRequest putObjectRequest = new PutObjectRequest(BUCKET_NAME, "sample_sdc.sdc", bIn, metadata);
      s3client.putObject(putObjectRequest);
    }

    //xml
    try (InputStream in = Resources.getResource("sample_xml.xml").openStream()) {
      PutObjectRequest putObjectRequest = new PutObjectRequest(BUCKET_NAME, "sample_xml.xml", in, new ObjectMetadata());
      s3client.putObject(putObjectRequest);
    }

    //json
    try (InputStream in = Resources.getResource("sample_json.json").openStream()) {
      PutObjectRequest putObjectRequest = new PutObjectRequest(BUCKET_NAME, "sample_json.json", in, new ObjectMetadata());
      s3client.putObject(putObjectRequest);
    }
    //log
    try (InputStream in = Resources.getResource("sample_log.log").openStream()) {
      PutObjectRequest putObjectRequest = new PutObjectRequest(BUCKET_NAME, "sample_log.log", in, new ObjectMetadata());
      s3client.putObject(putObjectRequest);
    }

    //avro
    try (InputStream in = Resources.getResource("sample_avro.avro").openStream()) {
      PutObjectRequest putObjectRequest = new PutObjectRequest(BUCKET_NAME, "sample_avro.avro", in, new ObjectMetadata());
      s3client.putObject(putObjectRequest);
    }

    int count = 0;
    if(s3client.doesBucketExist(BUCKET_NAME)) {
      for(S3ObjectSummary s : S3Objects.withPrefix(s3client, BUCKET_NAME, "")) {
        System.out.println(s.getKey());
        count++;
      }
    }
    Assert.assertEquals(6, count);
  }

  @Test
  public void testProduceLogFile() throws Exception {
    AmazonS3Source source = createSourceLog();
    SourceRunner runner = new SourceRunner.Builder(AmazonS3DSource.class, source).addOutputLane("lane").build();
    runner.runInit();
    try {
      int initialCount = getObjectCount(s3client, BUCKET_NAME);

      List<Record> allRecords = new ArrayList<>();
      String offset = null;
      for(int i = 0; i < 2; i++) {
        BatchMaker batchMaker = SourceRunner.createTestBatchMaker("lane");
        offset = source.produce(offset, 60000, batchMaker);
        Assert.assertNotNull(offset);

        StageRunner.Output output = SourceRunner.getOutput(batchMaker);
        List<Record> records = output.getRecords().get("lane");
        allRecords.addAll(records);
      }

      Assert.assertEquals(10, allRecords.size());
      Assert.assertTrue(offset.contains("sample_log.log::-1"));
      Assert.assertEquals(initialCount, getObjectCount(s3client, BUCKET_NAME));

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testProduceDelimitedFile() throws Exception {
    AmazonS3Source source = createSourceDelimited();
    SourceRunner runner = new SourceRunner.Builder(AmazonS3DSource.class, source).addOutputLane("lane").build();
    runner.runInit();
    try {
      int initialCount = getObjectCount(s3client, BUCKET_NAME);

      List<Record> allRecords = new ArrayList<>();
      String offset = null;
      for(int i = 0; i < 2; i++) {
        BatchMaker batchMaker = SourceRunner.createTestBatchMaker("lane");
        offset = source.produce(offset, 60000, batchMaker);
        Assert.assertNotNull(offset);

        StageRunner.Output output = SourceRunner.getOutput(batchMaker);
        List<Record> records = output.getRecords().get("lane");
        allRecords.addAll(records);
      }

      Assert.assertEquals(12, allRecords.size());
      Assert.assertTrue(offset.contains("sample_csv.csv::-1"));
      Assert.assertEquals(initialCount, getObjectCount(s3client, BUCKET_NAME));

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testProduceSdcFile() throws Exception {
    AmazonS3Source source = createSourceSdc();
    SourceRunner runner = new SourceRunner.Builder(AmazonS3DSource.class, source).addOutputLane("lane").build();
    runner.runInit();
    try {
      int initialCount = getObjectCount(s3client, BUCKET_NAME);

      List<Record> allRecords = new ArrayList<>();
      String offset = null;
      for(int i = 0; i < 2; i++) {
        BatchMaker batchMaker = SourceRunner.createTestBatchMaker("lane");
        offset = source.produce(offset, 60000, batchMaker);
        Assert.assertNotNull(offset);

        StageRunner.Output output = SourceRunner.getOutput(batchMaker);
        List<Record> records = output.getRecords().get("lane");
        allRecords.addAll(records);
      }

      Assert.assertEquals(1023, allRecords.size());
      Assert.assertTrue(offset.contains("sample_sdc.sdc::-1"));
      Assert.assertEquals(initialCount, getObjectCount(s3client, BUCKET_NAME));

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testProduceXmlFile() throws Exception {
    AmazonS3Source source = createSourceXml();
    SourceRunner runner = new SourceRunner.Builder(AmazonS3DSource.class, source).addOutputLane("lane").build();
    runner.runInit();
    try {
      int initialCount = getObjectCount(s3client, BUCKET_NAME);

      List<Record> allRecords = new ArrayList<>();
      String offset = null;
      for(int i = 0; i < 2; i++) {
        BatchMaker batchMaker = SourceRunner.createTestBatchMaker("lane");
        offset = source.produce(offset, 60000, batchMaker);
        Assert.assertNotNull(offset);

        StageRunner.Output output = SourceRunner.getOutput(batchMaker);
        List<Record> records = output.getRecords().get("lane");
        allRecords.addAll(records);
      }

      Assert.assertEquals(12, allRecords.size());
      Assert.assertTrue(offset.contains("sample_xml.xml::-1"));
      Assert.assertEquals(initialCount, getObjectCount(s3client, BUCKET_NAME));

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testProduceJsonFile() throws Exception {
    AmazonS3Source source = createSourceJson();
    SourceRunner runner = new SourceRunner.Builder(AmazonS3DSource.class, source).addOutputLane("lane").build();
    runner.runInit();
    try {
      int initialCount = getObjectCount(s3client, BUCKET_NAME);

      List<Record> allRecords = new ArrayList<>();
      String offset = null;
      for(int i = 0; i < 2; i++) {
        BatchMaker batchMaker = SourceRunner.createTestBatchMaker("lane");
        offset = source.produce(offset, 60000, batchMaker);
        Assert.assertNotNull(offset);

        StageRunner.Output output = SourceRunner.getOutput(batchMaker);
        List<Record> records = output.getRecords().get("lane");
        allRecords.addAll(records);
      }

      Assert.assertEquals(3, allRecords.size());
      Assert.assertTrue(offset.contains("sample_json.json::-1"));
      Assert.assertEquals(initialCount, getObjectCount(s3client, BUCKET_NAME));

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testProduceAvroFile() throws Exception {
    AmazonS3Source source = createSourceAvro();
    SourceRunner runner = new SourceRunner.Builder(AmazonS3DSource.class, source).addOutputLane("lane").build();
    runner.runInit();
    try {
      int initialCount = getObjectCount(s3client, BUCKET_NAME);

      List<Record> allRecords = new ArrayList<>();
      String offset = null;
      for(int i = 0; i < 5; i++) {
        BatchMaker batchMaker = SourceRunner.createTestBatchMaker("lane");
        offset = source.produce(offset, 1000, batchMaker);
        Assert.assertNotNull(offset);

        StageRunner.Output output = SourceRunner.getOutput(batchMaker);
        List<Record> records = output.getRecords().get("lane");
        allRecords.addAll(records);
      }

      Assert.assertEquals(3, allRecords.size());
      Assert.assertTrue(offset.contains("sample_avro.avro::-1"));
      Assert.assertEquals(initialCount, getObjectCount(s3client, BUCKET_NAME));

    } finally {
      runner.runDestroy();
    }
  }

  public void verifyStreamCorrectness(InputStream is1, InputStream is2) throws Exception {
    int totalBytesRead1 = 0, totalBytesRead2 = 0;
    int a = 0, b = 0;
    while (a != -1 || b != -1) {
      totalBytesRead1 = ((a = is1.read()) != -1)? totalBytesRead1 + 1 : totalBytesRead1;
      totalBytesRead2 = ((b = is2.read()) != -1)? totalBytesRead2 + 1 : totalBytesRead2;
      Assert.assertEquals(a, b);
    }
    Assert.assertEquals(totalBytesRead1, totalBytesRead2);
  }


  @Test
  public void testWholeFile() throws Exception {
    AmazonS3Source source = createSourceWholeFile();
    SourceRunner runner = new SourceRunner.Builder(AmazonS3DSource.class, source).addOutputLane("lane").build();
    runner.runInit();

    Stage.Context context = ((Stage.Context) Whitebox.getInternalState(source, "context"));

    String offset = null;
    try {
      Iterator<S3ObjectSummary> s3ObjectSummaryIterator = S3Objects.inBucket(s3client, BUCKET_NAME).iterator();
      Map<Pair<String, String>,S3ObjectSummary> s3ObjectSummaries = new HashMap<>();
      while (s3ObjectSummaryIterator.hasNext()) {
        S3ObjectSummary s3ObjectSummary = s3ObjectSummaryIterator.next();
        s3ObjectSummaries.put(Pair.of(s3ObjectSummary.getBucketName(), s3ObjectSummary.getKey()), s3ObjectSummary);
      }
      int numRecords = 0;
      do {
        BatchMaker batchMaker = SourceRunner.createTestBatchMaker("lane");
        offset = source.produce(offset, 1000, batchMaker);
        StageRunner.Output output = SourceRunner.getOutput(batchMaker);
        List<Record> records = output.getRecords().get("lane");
        Record record = records.get(0);
        Assert.assertTrue(record.has(FileRefUtil.FILE_INFO_FIELD_PATH + "/bucket"));
        Assert.assertTrue(record.has(FileRefUtil.FILE_INFO_FIELD_PATH + "/objectKey"));
        Assert.assertTrue(record.has(FileRefUtil.FILE_INFO_FIELD_PATH + "/owner"));
        Assert.assertTrue(record.has(FileRefUtil.FILE_INFO_FIELD_PATH + "/size"));

        String actualBucketName = record.get(FileRefUtil.FILE_INFO_FIELD_PATH + "/bucket").getValueAsString();
        String actualObjectKey = record.get(FileRefUtil.FILE_INFO_FIELD_PATH + "/objectKey").getValueAsString();

        S3ObjectSummary s3ObjectSummary = s3ObjectSummaries.get(Pair.of(actualBucketName, actualObjectKey));

        Assert.assertEquals(s3ObjectSummary.getBucketName(), actualBucketName);
        Assert.assertEquals(s3ObjectSummary.getKey(), actualObjectKey);

        Assert.assertEquals(
            s3ObjectSummary.getLastModified(),
            record.get(FileRefUtil.FILE_INFO_FIELD_PATH + "/" + Headers.LAST_MODIFIED).getValueAsDate()
        );
        Assert.assertEquals(s3ObjectSummary.getOwner().toString(), record.get(FileRefUtil.FILE_INFO_FIELD_PATH + "/owner").getValueAsString());
        Assert.assertEquals(s3ObjectSummary.getSize(), record.get(FileRefUtil.FILE_INFO_FIELD_PATH + "/size").getValueAsLong());
        //Check file content

        verifyStreamCorrectness(
            AmazonS3Util.getObject(s3client, BUCKET_NAME, s3ObjectSummary.getKey(), false, null, null).getObjectContent(),
            record.get(FileRefUtil.FILE_REF_FIELD_PATH).getValueAsFileRef().createInputStream(
                context,
                InputStream.class
            )
        );
        numRecords++;
        //Current Source limitation is only one object can be listed
        //and maxBatchSize is honored only for the current Object
        //as a result only one record (for the file) is produced
        //for a batch.
      } while (numRecords < s3ObjectSummaries.size());
    } finally {
      runner.runDestroy();
    }
  }

  private AmazonS3Source createSourceLog() {

    S3ConfigBean s3ConfigBean = new S3ConfigBean();
    s3ConfigBean.basicConfig = new BasicConfig();
    s3ConfigBean.basicConfig.maxWaitTime = 1000;
    s3ConfigBean.basicConfig.maxBatchSize = 60000;

    s3ConfigBean.sseConfig = new S3SSEConfigBean();
    s3ConfigBean.sseConfig.useCustomerSSEKey = false;

    s3ConfigBean.dataFormatConfig = new DataParserFormatConfig();
    s3ConfigBean.dataFormat = DataFormat.LOG;
    s3ConfigBean.dataFormatConfig.logMode = LogMode.COMMON_LOG_FORMAT;
    s3ConfigBean.dataFormatConfig.logMaxObjectLen = 1024;
    s3ConfigBean.dataFormatConfig.charset = "UTF-8";

    s3ConfigBean.errorConfig = new S3ErrorConfig();
    s3ConfigBean.errorConfig.errorHandlingOption = PostProcessingOptions.NONE;

    s3ConfigBean.postProcessingConfig = new S3PostProcessingConfig();
    s3ConfigBean.postProcessingConfig.postProcessing = PostProcessingOptions.NONE;

    s3ConfigBean.s3FileConfig = new S3FileConfig();
    s3ConfigBean.s3FileConfig.overrunLimit = 65;
    s3ConfigBean.s3FileConfig.prefixPattern = "*.log";

    s3ConfigBean.s3Config = new S3ConnectionSourceConfig();
    s3ConfigBean.s3Config.region = AWSRegions.OTHER;
    s3ConfigBean.s3Config.endpoint = "http://localhost:" + port;
    s3ConfigBean.s3Config.bucket = BUCKET_NAME;
    s3ConfigBean.s3Config.awsConfig = new AWSConfig();
    s3ConfigBean.s3Config.awsConfig.awsAccessKeyId = () -> "foo";
    s3ConfigBean.s3Config.awsConfig.awsSecretAccessKey = () -> "bar";
    s3ConfigBean.s3Config.awsConfig.disableChunkedEncoding = true;
    s3ConfigBean.s3Config.commonPrefix = "";
    s3ConfigBean.s3Config.delimiter = "/";
    s3ConfigBean.proxyConfig = new ProxyConfig();
    return new AmazonS3Source(s3ConfigBean);
  }

  private AmazonS3Source createSourceDelimited() {

    S3ConfigBean s3ConfigBean = new S3ConfigBean();
    s3ConfigBean.basicConfig = new BasicConfig();
    s3ConfigBean.basicConfig.maxWaitTime = 1000;
    s3ConfigBean.basicConfig.maxBatchSize = 60000;

    s3ConfigBean.sseConfig = new S3SSEConfigBean();
    s3ConfigBean.sseConfig.useCustomerSSEKey = false;

    s3ConfigBean.dataFormatConfig = new DataParserFormatConfig();
    s3ConfigBean.dataFormat = DataFormat.DELIMITED;
    s3ConfigBean.dataFormatConfig.csvFileFormat = CsvMode.CSV;
    s3ConfigBean.dataFormatConfig.csvMaxObjectLen = 1024;
    s3ConfigBean.dataFormatConfig.csvHeader = CsvHeader.IGNORE_HEADER;
    s3ConfigBean.dataFormatConfig.csvRecordType = CsvRecordType.LIST;
    s3ConfigBean.dataFormatConfig.charset = "UTF-8";

    s3ConfigBean.errorConfig = new S3ErrorConfig();
    s3ConfigBean.errorConfig.errorHandlingOption = PostProcessingOptions.NONE;

    s3ConfigBean.postProcessingConfig = new S3PostProcessingConfig();
    s3ConfigBean.postProcessingConfig.postProcessing = PostProcessingOptions.NONE;

    s3ConfigBean.s3FileConfig = new S3FileConfig();
    s3ConfigBean.s3FileConfig.overrunLimit = 65;
    s3ConfigBean.s3FileConfig.prefixPattern = "*.csv";

    s3ConfigBean.s3Config = new S3ConnectionSourceConfig();
    s3ConfigBean.s3Config.region = AWSRegions.OTHER;
    s3ConfigBean.s3Config.endpoint = "http://localhost:" + port;
    s3ConfigBean.s3Config.bucket = BUCKET_NAME;
    s3ConfigBean.s3Config.awsConfig = new AWSConfig();
    s3ConfigBean.s3Config.awsConfig.awsAccessKeyId = () -> "foo";
    s3ConfigBean.s3Config.awsConfig.awsSecretAccessKey = () -> "bar";
    s3ConfigBean.s3Config.awsConfig.disableChunkedEncoding = true;
    s3ConfigBean.s3Config.commonPrefix = "";
    s3ConfigBean.s3Config.delimiter = "/";
    s3ConfigBean.proxyConfig = new ProxyConfig();
    return new AmazonS3Source(s3ConfigBean);
  }

  private AmazonS3Source createSourceSdc() {

    S3ConfigBean s3ConfigBean = new S3ConfigBean();
    s3ConfigBean.basicConfig = new BasicConfig();
    s3ConfigBean.basicConfig.maxWaitTime = 1000;
    s3ConfigBean.basicConfig.maxBatchSize = 60000;

    s3ConfigBean.sseConfig = new S3SSEConfigBean();
    s3ConfigBean.sseConfig.useCustomerSSEKey = false;

    s3ConfigBean.dataFormatConfig = new DataParserFormatConfig();
    s3ConfigBean.dataFormat = DataFormat.SDC_JSON;
    s3ConfigBean.dataFormatConfig.charset = "UTF-8";

    s3ConfigBean.errorConfig = new S3ErrorConfig();
    s3ConfigBean.errorConfig.errorHandlingOption = PostProcessingOptions.NONE;

    s3ConfigBean.postProcessingConfig = new S3PostProcessingConfig();
    s3ConfigBean.postProcessingConfig.postProcessing = PostProcessingOptions.NONE;

    s3ConfigBean.s3FileConfig = new S3FileConfig();
    s3ConfigBean.s3FileConfig.overrunLimit = 65;
    s3ConfigBean.s3FileConfig.prefixPattern = "*.sdc";

    s3ConfigBean.s3Config = new S3ConnectionSourceConfig();
    s3ConfigBean.s3Config.region = AWSRegions.OTHER;
    s3ConfigBean.s3Config.endpoint = "http://localhost:" + port;
    s3ConfigBean.s3Config.bucket = BUCKET_NAME;
    s3ConfigBean.s3Config.awsConfig = new AWSConfig();
    s3ConfigBean.s3Config.awsConfig.awsAccessKeyId = () -> "foo";
    s3ConfigBean.s3Config.awsConfig.awsSecretAccessKey = () -> "bar";
    s3ConfigBean.s3Config.awsConfig.disableChunkedEncoding = true;
    s3ConfigBean.s3Config.commonPrefix = "";
    s3ConfigBean.s3Config.delimiter = "/";
    s3ConfigBean.proxyConfig = new ProxyConfig();
    return new AmazonS3Source(s3ConfigBean);
  }

  private AmazonS3Source createSourceXml() {

    S3ConfigBean s3ConfigBean = new S3ConfigBean();
    s3ConfigBean.basicConfig = new BasicConfig();
    s3ConfigBean.basicConfig.maxWaitTime = 1000;
    s3ConfigBean.basicConfig.maxBatchSize = 60000;

    s3ConfigBean.sseConfig = new S3SSEConfigBean();
    s3ConfigBean.sseConfig.useCustomerSSEKey = false;

    s3ConfigBean.dataFormatConfig = new DataParserFormatConfig();
    s3ConfigBean.dataFormat = DataFormat.XML;
    s3ConfigBean.dataFormatConfig.charset = "UTF-8";
    s3ConfigBean.dataFormatConfig.xmlMaxObjectLen = 1024;
    s3ConfigBean.dataFormatConfig.xmlRecordElement = "book";

    s3ConfigBean.errorConfig = new S3ErrorConfig();
    s3ConfigBean.errorConfig.errorHandlingOption = PostProcessingOptions.NONE;

    s3ConfigBean.postProcessingConfig = new S3PostProcessingConfig();
    s3ConfigBean.postProcessingConfig.postProcessing = PostProcessingOptions.NONE;

    s3ConfigBean.s3FileConfig = new S3FileConfig();
    s3ConfigBean.s3FileConfig.overrunLimit = 65;
    s3ConfigBean.s3FileConfig.prefixPattern = "*.xml";

    s3ConfigBean.s3Config = new S3ConnectionSourceConfig();
    s3ConfigBean.s3Config.region = AWSRegions.OTHER;
    s3ConfigBean.s3Config.endpoint = "http://localhost:" + port;
    s3ConfigBean.s3Config.bucket = BUCKET_NAME;
    s3ConfigBean.s3Config.awsConfig = new AWSConfig();
    s3ConfigBean.s3Config.awsConfig.awsAccessKeyId = () -> "foo";
    s3ConfigBean.s3Config.awsConfig.awsSecretAccessKey = () -> "bar";
    s3ConfigBean.s3Config.awsConfig.disableChunkedEncoding = true;
    s3ConfigBean.s3Config.commonPrefix = "";
    s3ConfigBean.s3Config.delimiter = "/";
    s3ConfigBean.proxyConfig = new ProxyConfig();
    return new AmazonS3Source(s3ConfigBean);
  }

  private AmazonS3Source createSourceJson() {

    S3ConfigBean s3ConfigBean = new S3ConfigBean();
    s3ConfigBean.basicConfig = new BasicConfig();
    s3ConfigBean.basicConfig.maxWaitTime = 1000;
    s3ConfigBean.basicConfig.maxBatchSize = 60000;

    s3ConfigBean.sseConfig = new S3SSEConfigBean();
    s3ConfigBean.sseConfig.useCustomerSSEKey = false;

    s3ConfigBean.dataFormatConfig = new DataParserFormatConfig();
    s3ConfigBean.dataFormat = DataFormat.JSON;
    s3ConfigBean.dataFormatConfig.charset = "UTF-8";
    s3ConfigBean.dataFormatConfig.jsonMaxObjectLen = 10000;
    s3ConfigBean.dataFormatConfig.jsonContent = JsonMode.MULTIPLE_OBJECTS;

    s3ConfigBean.errorConfig = new S3ErrorConfig();
    s3ConfigBean.errorConfig.errorHandlingOption = PostProcessingOptions.NONE;

    s3ConfigBean.postProcessingConfig = new S3PostProcessingConfig();
    s3ConfigBean.postProcessingConfig.postProcessing = PostProcessingOptions.NONE;

    s3ConfigBean.s3FileConfig = new S3FileConfig();
    s3ConfigBean.s3FileConfig.overrunLimit = 65;
    s3ConfigBean.s3FileConfig.prefixPattern = "*.json";

    s3ConfigBean.s3Config = new S3ConnectionSourceConfig();
    s3ConfigBean.s3Config.region = AWSRegions.OTHER;
    s3ConfigBean.s3Config.endpoint = "http://localhost:" + port;
    s3ConfigBean.s3Config.bucket = BUCKET_NAME;
    s3ConfigBean.s3Config.awsConfig = new AWSConfig();
    s3ConfigBean.s3Config.awsConfig.awsAccessKeyId = () -> "foo";
    s3ConfigBean.s3Config.awsConfig.awsSecretAccessKey = () -> "bar";
    s3ConfigBean.s3Config.awsConfig.disableChunkedEncoding = true;
    s3ConfigBean.s3Config.commonPrefix = "";
    s3ConfigBean.s3Config.delimiter = "/";
    s3ConfigBean.proxyConfig = new ProxyConfig();
    return new AmazonS3Source(s3ConfigBean);
  }

  private AmazonS3Source createSourceAvro() {

    S3ConfigBean s3ConfigBean = new S3ConfigBean();
    s3ConfigBean.basicConfig = new BasicConfig();
    s3ConfigBean.basicConfig.maxWaitTime = 1000;
    s3ConfigBean.basicConfig.maxBatchSize = 60000;

    s3ConfigBean.sseConfig = new S3SSEConfigBean();
    s3ConfigBean.sseConfig.useCustomerSSEKey = false;

    s3ConfigBean.dataFormatConfig = new DataParserFormatConfig();
    s3ConfigBean.dataFormat = DataFormat.AVRO;
    s3ConfigBean.dataFormatConfig.charset = "UTF-8";
    s3ConfigBean.dataFormatConfig.avroSchemaSource = OriginAvroSchemaSource.SOURCE;
    s3ConfigBean.dataFormatConfig.avroSchema = null;

    s3ConfigBean.errorConfig = new S3ErrorConfig();
    s3ConfigBean.errorConfig.errorHandlingOption = PostProcessingOptions.NONE;

    s3ConfigBean.postProcessingConfig = new S3PostProcessingConfig();
    s3ConfigBean.postProcessingConfig.postProcessing = PostProcessingOptions.NONE;

    s3ConfigBean.s3FileConfig = new S3FileConfig();
    s3ConfigBean.s3FileConfig.overrunLimit = 128;
    s3ConfigBean.s3FileConfig.prefixPattern = "*.avro";

    s3ConfigBean.s3Config = new S3ConnectionSourceConfig();
    s3ConfigBean.s3Config.region = AWSRegions.OTHER;
    s3ConfigBean.s3Config.endpoint = "http://localhost:" + port;
    s3ConfigBean.s3Config.bucket = BUCKET_NAME;
    s3ConfigBean.s3Config.awsConfig = new AWSConfig();
    s3ConfigBean.s3Config.awsConfig.awsAccessKeyId = () -> "foo";
    s3ConfigBean.s3Config.awsConfig.awsSecretAccessKey = () -> "bar";
    s3ConfigBean.s3Config.awsConfig.disableChunkedEncoding = true;
    s3ConfigBean.s3Config.commonPrefix = "";
    s3ConfigBean.s3Config.delimiter = "/";
    s3ConfigBean.proxyConfig = new ProxyConfig();
    return new AmazonS3Source(s3ConfigBean);
  }

  private AmazonS3Source createSourceWholeFile() {
    S3ConfigBean s3ConfigBean = new S3ConfigBean();
    s3ConfigBean.basicConfig = new BasicConfig();
    s3ConfigBean.basicConfig.maxWaitTime = 1000;
    s3ConfigBean.basicConfig.maxBatchSize = 60000;

    s3ConfigBean.sseConfig = new S3SSEConfigBean();
    s3ConfigBean.sseConfig.useCustomerSSEKey = false;

    s3ConfigBean.dataFormatConfig = new DataParserFormatConfig();
    s3ConfigBean.dataFormat = DataFormat.WHOLE_FILE;
    s3ConfigBean.dataFormatConfig.charset = "UTF-8";
    s3ConfigBean.dataFormatConfig.wholeFileMaxObjectLen = 1024;
    s3ConfigBean.dataFormatConfig.verifyChecksum = true;

    s3ConfigBean.errorConfig = new S3ErrorConfig();
    s3ConfigBean.errorConfig.errorHandlingOption = PostProcessingOptions.NONE;

    s3ConfigBean.postProcessingConfig = new S3PostProcessingConfig();
    s3ConfigBean.postProcessingConfig.postProcessing = PostProcessingOptions.NONE;

    s3ConfigBean.s3FileConfig = new S3FileConfig();
    s3ConfigBean.s3FileConfig.overrunLimit = 128;
    s3ConfigBean.s3FileConfig.prefixPattern = "*";

    s3ConfigBean.s3Config = new S3ConnectionSourceConfig();
    s3ConfigBean.s3Config.region = AWSRegions.OTHER;
    s3ConfigBean.s3Config.endpoint = "http://localhost:" + port;
    s3ConfigBean.s3Config.bucket = BUCKET_NAME;
    s3ConfigBean.s3Config.awsConfig = new AWSConfig();
    s3ConfigBean.s3Config.awsConfig.awsAccessKeyId = () -> "foo";
    s3ConfigBean.s3Config.awsConfig.awsSecretAccessKey = () -> "bar";
    s3ConfigBean.s3Config.awsConfig.disableChunkedEncoding = true;
    s3ConfigBean.s3Config.commonPrefix = "";
    s3ConfigBean.s3Config.delimiter = "/";
    s3ConfigBean.proxyConfig = new ProxyConfig();
    return new AmazonS3Source(s3ConfigBean);
  }

  private int getObjectCount(AmazonS3 s3Client, String bucket) {
    int count = 0;
    for(S3ObjectSummary s : S3Objects.inBucket(s3Client, bucket)) {
      count++;
    }
    return count;
  }
}
