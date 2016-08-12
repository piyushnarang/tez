/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tez.dag.api.client;

import static junit.framework.TestCase.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;

import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.exceptions.ApplicationNotFoundException;
import org.apache.tez.common.counters.TezCounters;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.records.DAGProtos.DAGInformationProto;
import org.apache.tez.dag.api.records.DAGProtos.VertexInformationProto;
import org.apache.tez.dag.api.records.DAGProtos.TaskStateProto;
import org.apache.tez.dag.api.records.DAGProtos.TezCounterProto;
import org.apache.tez.dag.api.records.DAGProtos.TezCountersProto;
import org.apache.tez.dag.api.records.DAGProtos.TezCounterGroupProto;
import org.apache.tez.dag.api.records.DAGProtos.TaskInformationProto;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class TestATSHttpClient {

  @Before
  public void setup() {
    // Disable tests if hadoop version is less than 2.4.0
    // as Timeline is not supported in 2.2.x or 2.3.x
    // If enabled with the lower versions, tests fail due to incompatible use of an API
    // YarnConfiguration::useHttps which only exists in versions 2.4 and higher
    String hadoopVersion = System.getProperty("tez.hadoop.version");
    Assume.assumeFalse(hadoopVersion.startsWith("2.2.") || hadoopVersion.startsWith("2.3."));
  }

  @Test(timeout = 5000)
  public void testGetDagStatusThrowsExceptionOnEmptyJson() throws TezException {
    ApplicationId mockAppId = mock(ApplicationId.class);
    DAGClientTimelineImpl httpClient = new DAGClientTimelineImpl(mockAppId, "EXAMPLE_DAG_ID",
        new TezConfiguration(), null, 0);
    DAGClientTimelineImpl spyClient = spy(httpClient);
    spyClient.baseUri = "http://yarn.ats.webapp/ws/v1/timeline";
    final String expectedDagUrl = "http://yarn.ats.webapp/ws/v1/timeline/TEZ_DAG_ID/EXAMPLE_DAG_ID" +
        "?fields=primaryfilters,otherinfo";

    doReturn(new JSONObject()).when(spyClient).getJsonRootEntity(expectedDagUrl);
    boolean exceptionHappened = false;
    try {
      spyClient.getDAGStatus(null);
    } catch (TezException e) {
      exceptionHappened = true;
    } catch (IOException e) {
      fail("should not come here");
    }

    Assert.assertTrue("Expected TezException but did not happen", exceptionHappened);
    verify(spyClient).getJsonRootEntity(expectedDagUrl);
  }

  @Test(timeout = 5000)
  public void testGetDagStatusSimple() throws TezException, JSONException, IOException {
    DAGClientTimelineImpl
        httpClient = new DAGClientTimelineImpl(mock(ApplicationId.class),"EXAMPLE_DAG_ID",
        new TezConfiguration(), null, 0);
    DAGClientTimelineImpl spyClient = spy(httpClient);
    spyClient.baseUri = "http://yarn.ats.webapp/ws/v1/timeline";
    final String expectedDagUrl = "http://yarn.ats.webapp/ws/v1/timeline/TEZ_DAG_ID/EXAMPLE_DAG_ID" +
        "?fields=primaryfilters,otherinfo";
    final String expectedVertexUrl = "http://yarn.ats.webapp/ws/v1/timeline/TEZ_VERTEX_ID" +
        "?primaryFilter=TEZ_DAG_ID:EXAMPLE_DAG_ID&fields=primaryfilters,otherinfo";

    Set<StatusGetOpts> statusOptions = new HashSet<>(1);
    statusOptions.add(StatusGetOpts.GET_COUNTERS);


    final String jsonDagData =
            "{ " +
            "  otherinfo: { " +
            "    status: 'SUCCEEDED'," +
            "    diagnostics: 'SAMPLE_DIAGNOSTICS'," +
            "    counters: { counterGroups: [ " +
            "      { counterGroupName: 'CG1', counterGroupDisplayName: 'CGD1', counters: [" +
            "        {counterName:'C1', counterDisplayName: 'CD1', counterValue: 1 }," +
            "        {counterName:'C2', counterDisplayName: 'CD2', counterValue: 2 }" +
            "      ]}" +
            "    ]}" +
            "  }" +
            "}";

    final String jsonVertexData = "{entities:[ " +
        "{otherinfo: {vertexName:'v1', numTasks:5,numFailedTasks:1,numSucceededTasks:2," +
          "numKilledTasks:3,numCompletedTasks:3}}," +
        "{otherinfo: {vertexName:'v2',numTasks:10,numFailedTasks:1,numSucceededTasks:5," +
          "numKilledTasks:3,numCompletedTasks:4}}" +
        "]}";

    doReturn(new JSONObject(jsonDagData)).when(spyClient).getJsonRootEntity(expectedDagUrl);
    doReturn(new JSONObject(jsonVertexData)).when(spyClient).getJsonRootEntity(expectedVertexUrl);

    DAGStatus dagStatus = spyClient.getDAGStatus(statusOptions);

    Assert.assertEquals("DAG State", DAGStatus.State.SUCCEEDED, dagStatus.getState());
    Assert.assertEquals("DAG Diagnostics size", 1, dagStatus.getDiagnostics().size());
    Assert.assertEquals("DAG diagnostics detail", "SAMPLE_DIAGNOSTICS",
        dagStatus.getDiagnostics().get(0));
    Assert.assertEquals("Counters Size", 2, dagStatus.getDAGCounters().countCounters());
    Assert.assertEquals("Counter Value", 1,
        dagStatus.getDAGCounters().getGroup("CG1").findCounter("C1").getValue());
    Assert.assertEquals("total tasks", 15, dagStatus.getDAGProgress().getTotalTaskCount());
    Assert.assertEquals("failed tasks", 2, dagStatus.getDAGProgress().getFailedTaskCount());
    Assert.assertEquals("killed tasks", 6, dagStatus.getDAGProgress().getKilledTaskCount());
    Assert.assertEquals("succeeded tasks", 7, dagStatus.getDAGProgress().getSucceededTaskCount());
    Assert.assertEquals("running tasks", 8, dagStatus.getDAGProgress().getRunningTaskCount());
    final Map<String, Progress> vertexProgress = dagStatus.getVertexProgress();
    Assert.assertEquals("vertex progress count", 2, vertexProgress.size());
    Assert.assertTrue("vertex name1", vertexProgress.containsKey("v1"));
    Assert.assertTrue("vertex name2", vertexProgress.containsKey("v2"));
  }

  @Test(timeout = 5000)
  public void testGetVertexStatusSimple() throws JSONException, TezException, IOException {
    DAGClientTimelineImpl
        httpClient = new DAGClientTimelineImpl(mock(ApplicationId.class), "EXAMPLE_DAG_ID",
        new TezConfiguration(), null, 0);
    DAGClientTimelineImpl spyClient = spy(httpClient);
    spyClient.baseUri = "http://yarn.ats.webapp/ws/v1/timeline";
    final String expectedVertexUrl = "http://yarn.ats.webapp/ws/v1/timeline/TEZ_VERTEX_ID" +
        "?primaryFilter=TEZ_DAG_ID:EXAMPLE_DAG_ID&secondaryFilter=vertexName:vertex1name&" +
        "fields=primaryfilters,otherinfo";

    Set<StatusGetOpts> statusOptions = new HashSet<>(1);
    statusOptions.add(StatusGetOpts.GET_COUNTERS);

    final String jsonData = "{entities:[ {otherinfo:{numFailedTasks:1,numSucceededTasks:2," +
        "status:'SUCCEEDED', vertexName:'vertex1name', numTasks:4, numKilledTasks: 3, " +
        "numCompletedTasks: 4, diagnostics: 'diagnostics1', " +
        "counters: { counterGroups: [ " +
        "      { counterGroupName: 'CG1', counterGroupDisplayName: 'CGD1', counters: [" +
        "        {counterName:'C1', counterDisplayName: 'CD1', counterValue: 1 }," +
        "        {counterName:'C2', counterDisplayName: 'CD2', counterValue: 2 }" +
        "      ]}" +
        "    ]}" +
        "}}]}";

    doReturn(new JSONObject(jsonData)).when(spyClient).getJsonRootEntity(expectedVertexUrl);

    VertexStatus vertexStatus = spyClient.getVertexStatus("vertex1name", statusOptions);
    Assert.assertEquals("status check", VertexStatus.State.SUCCEEDED, vertexStatus.getState());
    Assert.assertEquals("diagnostics", "diagnostics1", vertexStatus.getDiagnostics().get(0));
    final Progress progress = vertexStatus.getProgress();
    final TezCounters vertexCounters = vertexStatus.getVertexCounters();
    Assert.assertEquals("failed task count", 1, progress.getFailedTaskCount());
    Assert.assertEquals("suceeded task count", 2, progress.getSucceededTaskCount());
    Assert.assertEquals("killed task count", 3, progress.getKilledTaskCount());
    Assert.assertEquals("total task count", 4, progress.getTotalTaskCount());
    Assert.assertEquals("Counters Size", 2, vertexCounters.countCounters());
    Assert.assertEquals("Counter Value", 1,
        vertexCounters.getGroup("CG1").findCounter("C1").getValue());
  }

  @Test(timeout = 5000)
  public void testGetDAGInformation() throws JSONException, TezException, IOException, ApplicationNotFoundException {
    DAGClientTimelineImpl
      httpClient = new DAGClientTimelineImpl(mock(ApplicationId.class), "dag_1468518877269_2795_1",
      new TezConfiguration(), null, 0);
    DAGClientTimelineImpl spyClient = spy(httpClient);
    spyClient.baseUri = "http://yarn.ats.webapp/ws/v1/timeline";
    final String expectedDagInfoUrl = "http://yarn.ats.webapp/ws/v1/timeline/" +
      "TEZ_DAG_ID/dag_1468518877269_2795_1?fields=primaryfilters,otherinfo";

    final String jsonData = "{  \n" +
      "   entitytype:'TEZ_DAG_ID',\n" +
      "   entity:'dag_1468518877269_2795_1',\n" +
      "   starttime:1470167608648,\n" +
      "   primaryfilters : {  \n" +
      "      dagName:[ 'Test DAG'],\n" +
      "      status:['SUCCEEDED'],\n" +
      "      applicationId:['application_1468518877269_2795'],\n" +
      "   },\n" +
      "   otherinfo:{  \n" +
      "      numFailedTasks:0,\n" +
      "      vertexNameIdMapping:{'v1':'vertex_1468518877269_2795_1_01','v2':'vertex_1468518877269_2795_1_00'},\n" +
      "      status:'SUCCEEDED',\n" +
      "      dagPlan: { dagName:'Test DAG' },\n" +
      "      applicationId:'application_1468518877269_2795',\n" +
      "      endTime:1470167673199,\n" +
      "      counters:{ counterGroups:[ { counterGroupName:'group1',counters:[ { counterName:'c1',counterValue:46 } ]}]\n" +
      "      }\n" +
      "   }\n" +
      "}";

    doReturn(new JSONObject(jsonData)).when(spyClient).getJsonRootEntity(expectedDagInfoUrl);
    DAGInformation dagInformation = spyClient.getDAGInformation();

    VertexInformationProto vertex1 =
      VertexInformationProto.newBuilder()
        .setId("vertex_1468518877269_2795_1_01")
        .setName("v1").build();
    VertexInformationProto vertex2 =
      VertexInformationProto.newBuilder()
        .setId("vertex_1468518877269_2795_1_00")
        .setName("v2").build();
    List<VertexInformationProto> vertexInformationProtos = Lists.newArrayList(vertex1, vertex2);

    DAGInformationProto dagInformationProto = DAGInformationProto.newBuilder()
      .setDagId("dag_1468518877269_2795_1")
      .setName("Test DAG")
      .addAllVertices(vertexInformationProtos)
      .build();
    DAGInformation expectedDagInformation = new DAGInformation(dagInformationProto);
    Assert.assertEquals(expectedDagInformation, dagInformation);
  }

  @Test(timeout = 5000)
  public void testGetTaskInformation() throws JSONException, TezException, IOException, ApplicationNotFoundException {
    DAGClientTimelineImpl
      httpClient = new DAGClientTimelineImpl(mock(ApplicationId.class), "dag_1468518877269_2795_1",
      new TezConfiguration(), null, 0);

    DAGClientTimelineImpl spyClient = spy(httpClient);
    spyClient.baseUri = "http://yarn.ats.webapp/ws/v1/timeline";
    final String expectedTaskInfoUrl = "http://yarn.ats.webapp/ws/v1/timeline/" +
      "TEZ_TASK_ID/task_1468518877269_3815_1_01_000001?fields=primaryfilters,otherinfo";

    // todo: move json to resource files
    final String jsonData = "{  \n" +
      "   'entitytype':'TEZ_TASK_ID',\n" +
      "   'entity':'task_1468518877269_3815_1_01_000001',\n" +
      "   'starttime':1470778337429,\n" +
      "   'domain':'Tez_ATS_application_1468518877269_3815',\n" +
      "   'primaryfilters':{  \n" +
      "      'status':[  \n" +
      "         'SUCCEEDED'\n" +
      "      ],\n" +
      "      'applicationId':[  \n" +
      "         'application_1468518877269_3815'\n" +
      "      ],\n" +
      "      'TEZ_VERTEX_ID':[  \n" +
      "         'vertex_1468518877269_3815_1_01'\n" +
      "      ],\n" +
      "      'TEZ_DAG_ID':[  \n" +
      "         'dag_1468518877269_2795_1'\n" +
      "      ]\n" +
      "   },\n" +
      "   'otherinfo':{  \n" +
      "      'startTime':1470778337429,\n" +
      "      'status':'SUCCEEDED',\n" +
      "      'timeTaken':7111,\n" +
      "      'successfulAttemptId':'attempt_1468518877269_3815_1_01_000001_0',\n" +
      "      'scheduledTime':1470778337429,\n" +
      "      'numFailedTaskAttempts':0,\n" +
      "      'endTime':1470778368202,\n" +
      "      'diagnostics':'sample diagnostics',\n" +
      "      'counters':{ 'counterGroups':[ { 'counterGroupName':'group1', 'counters':[ { 'counterName':'c1', 'counterValue':1 } ] }]}\n" +
      "   }\n" +
      "}";

    doReturn(new JSONObject(jsonData)).when(spyClient).getJsonRootEntity(expectedTaskInfoUrl);
    TaskInformation taskInformation = spyClient.getTaskInformation("vertex_1468518877269_3815_1_01", "task_1468518877269_3815_1_01_000001");


    TezCountersProto taskCountersProto= TezCountersProto.newBuilder()
      .addCounterGroups(TezCounterGroupProto.newBuilder()
        .setDisplayName("group1")
        .setName("group1")
        .addCounters(TezCounterProto.newBuilder()
          .setDisplayName("c1")
          .setName("c1")
          .setValue(1)))
      .build();

    TaskInformationProto taskInformationProto = TaskInformationProto.newBuilder()
      .setId("task_1468518877269_3815_1_01_000001")
      .setDiagnostics("sample diagnostics")
      .setStartTime(1470778337429L)
      .setState(TaskStateProto.TASK_SUCCEEDED)
      .setScheduledTime(1470778337429L)
      .setEndTime(1470778368202L)
      .setSuccessfulAttemptId("attempt_1468518877269_3815_1_01_000001_0")
      .setTaskCounters(taskCountersProto)
      .build();

    Assert.assertEquals(new TaskInformation(taskInformationProto), taskInformation);
  }

  @Test(timeout = 5000)
  public void testGetTaskInformationList() throws JSONException, TezException, IOException, ApplicationNotFoundException {
    DAGClientTimelineImpl
      httpClient = new DAGClientTimelineImpl(mock(ApplicationId.class), "dag_1468518877269_2795_1",
      new TezConfiguration(), null, 0);

    DAGClientTimelineImpl spyClient = spy(httpClient);
    spyClient.baseUri = "http://yarn.ats.webapp/ws/v1/timeline";

    final String expectedTaskInfoUrl = "http://yarn.ats.webapp/ws/v1/timeline/" +
      "TEZ_TASK_ID?primaryFilter=TEZ_VERTEX_ID:vertex_1468518877269_3815_1_01&fields=primaryfilters,otherinfo&limit=2";

    // todo: move json to resource files
    final String jsonData = "{  \n" +
      "   'entities':[  \n" +
      "      {  \n" +
      "         'entitytype':'TEZ_TASK_ID',\n" +
      "         'entity':'task_1468518877269_3815_1_01_000001',\n" +
      "         'starttime':1470778337429,\n" +
      "         'domain':'Tez_ATS_application_1468518877269_3815',\n" +
      "         'primaryfilters':{  \n" +
      "            'status':[  \n" +
      "               'SUCCEEDED'\n" +
      "            ],\n" +
      "            'applicationId':[  \n" +
      "               'application_1468518877269_3815'\n" +
      "            ],\n" +
      "            'TEZ_VERTEX_ID':[  \n" +
      "               'vertex_1468518877269_3815_1_01'\n" +
      "            ],\n" +
      "            'TEZ_DAG_ID':[  \n" +
      "               'dag_1468518877269_3815_1'\n" +
      "            ]\n" +
      "         },\n" +
      "         'otherinfo':{  \n" +
      "            'startTime':1470778337429,\n" +
      "            'status':'SUCCEEDED',\n" +
      "            'timeTaken':7111,\n" +
      "            'successfulAttemptId':'attempt_1468518877269_3815_1_01_000001_0',\n" +
      "            'scheduledTime':1470778337429,\n" +
      "            'numFailedTaskAttempts':0,\n" +
      "            'endTime':1470778368101,\n" +
      "            'diagnostics':'sample diagnostics',\n" +
      "            'counters':{ 'counterGroups':[ { 'counterGroupName':'group1','counters':[ { 'counterName':'c1','counterValue':1 } ] } ] }\n" +
      "         }\n" +
      "      },\n" +
      "      {  \n" +
      "         'entitytype':'TEZ_TASK_ID',\n" +
      "         'entity':'task_1468518877269_3815_1_01_000002',\n" +
      "         'starttime':1470778337429,\n" +
      "         'domain':'Tez_ATS_application_1468518877269_3815',\n" +
      "         'primaryfilters':{  \n" +
      "            'status':[  \n" +
      "               'SUCCEEDED'\n" +
      "            ],\n" +
      "            'applicationId':[  \n" +
      "               'application_1468518877269_3815'\n" +
      "            ],\n" +
      "            'TEZ_VERTEX_ID':[  \n" +
      "               'vertex_1468518877269_3815_1_01'\n" +
      "            ],\n" +
      "            'TEZ_DAG_ID':[  \n" +
      "               'dag_1468518877269_3815_1'\n" +
      "            ]\n" +
      "         },\n" +
      "         'otherinfo':{  \n" +
      "            'startTime':1470778337429,\n" +
      "            'status':'SUCCEEDED',\n" +
      "            'timeTaken':7412,\n" +
      "            'successfulAttemptId':'attempt_1468518877269_3815_1_01_000002_0',\n" +
      "            'scheduledTime':1470778337429,\n" +
      "            'numFailedTaskAttempts':0,\n" +
      "            'endTime':1470778368101,\n" +
      "            'diagnostics':'sample diagnostics',\n" +
      "            'counters':{ 'counterGroups':[ { 'counterGroupName':'group1','counters':[ { 'counterName':'c1','counterValue':1 } ] } ] }\n" +
      "         }\n" +
      "      }\n" +
      "   ]\n" +
      "}";

    doReturn(new JSONObject(jsonData)).when(spyClient).getJsonRootEntity(expectedTaskInfoUrl);
    List<TaskInformation> taskInformationList = spyClient.getTaskInformation("vertex_1468518877269_3815_1_01", null, 2);

    TezCountersProto taskCountersProto = TezCountersProto.newBuilder()
      .addCounterGroups(TezCounterGroupProto.newBuilder()
        .setDisplayName("group1")
        .setName("group1")
        .addCounters(TezCounterProto.newBuilder()
          .setDisplayName("c1")
          .setName("c1")
          .setValue(1)))
      .build();

    TaskInformationProto taskInformationProto1 = TaskInformationProto.newBuilder()
      .setId("task_1468518877269_3815_1_01_000001")
      .setDiagnostics("sample diagnostics")
      .setStartTime(1470778337429L)
      .setState(TaskStateProto.TASK_SUCCEEDED)
      .setScheduledTime(1470778337429L)
      .setEndTime(1470778368101L)
      .setSuccessfulAttemptId("attempt_1468518877269_3815_1_01_000001_0")
      .setTaskCounters(taskCountersProto)
      .build();

    TaskInformationProto taskInformationProto2 =
      TaskInformationProto.newBuilder(taskInformationProto1)
        .setId("task_1468518877269_3815_1_01_000002")
        .setSuccessfulAttemptId("attempt_1468518877269_3815_1_01_000002_0")
        .build();

    List<TaskInformation> expectedTaskInformations = Lists.newArrayList(new TaskInformation(taskInformationProto1), new TaskInformation(taskInformationProto2));
    Assert.assertEquals(expectedTaskInformations, taskInformationList);
  }

  @Test(timeout = 5000)
  public void testGetTaskInformationWithStartList() throws JSONException, TezException, IOException, ApplicationNotFoundException {
    DAGClientTimelineImpl
      httpClient = new DAGClientTimelineImpl(mock(ApplicationId.class), "dag_1468518877269_2795_1",
      new TezConfiguration(), null, 0);

    DAGClientTimelineImpl spyClient = spy(httpClient);
    spyClient.baseUri = "http://yarn.ats.webapp/ws/v1/timeline";

    // test out with startTaskId = task2 (task_1468518877269_3815_1_01_000002)
    final String expectedTaskInfoWithStartUrl = "http://yarn.ats.webapp/ws/v1/timeline/" +
      "TEZ_TASK_ID?primaryFilter=TEZ_VERTEX_ID:vertex_1468518877269_3815_1_01&fields=primaryfilters,otherinfo&limit=2&fromId=task_1468518877269_3815_1_01_000002";

    // todo: move json to resource files
    final String jsonData = "{  \n" +
      "   'entities':[  \n" +
      "      {  \n" +
      "         'entitytype':'TEZ_TASK_ID',\n" +
      "         'entity':'task_1468518877269_3815_1_01_000002',\n" +
      "         'starttime':1470778337429,\n" +
      "         'domain':'Tez_ATS_application_1468518877269_3815',\n" +
      "         'primaryfilters':{  \n" +
      "            'status':[  \n" +
      "               'SUCCEEDED'\n" +
      "            ],\n" +
      "            'applicationId':[  \n" +
      "               'application_1468518877269_3815'\n" +
      "            ],\n" +
      "            'TEZ_VERTEX_ID':[  \n" +
      "               'vertex_1468518877269_3815_1_01'\n" +
      "            ],\n" +
      "            'TEZ_DAG_ID':[  \n" +
      "               'dag_1468518877269_3815_1'\n" +
      "            ]\n" +
      "         },\n" +
      "         'otherinfo':{  \n" +
      "            'startTime':1470778337429,\n" +
      "            'status':'SUCCEEDED',\n" +
      "            'timeTaken':7412,\n" +
      "            'successfulAttemptId':'attempt_1468518877269_3815_1_01_000002_0',\n" +
      "            'scheduledTime':1470778337429,\n" +
      "            'numFailedTaskAttempts':0,\n" +
      "            'endTime':1470778368101,\n" +
      "            'diagnostics':'sample diagnostics',\n" +
      "            'counters':{ 'counterGroups':[ { 'counterGroupName':'group1','counters':[ { 'counterName':'c1','counterValue':1 } ] } ] }\n" +
      "         }\n" +
      "      }\n" +
      "   ]\n" +
      "}";

    doReturn(new JSONObject(jsonData)).when(spyClient).getJsonRootEntity(expectedTaskInfoWithStartUrl);
    List<TaskInformation> taskInformationList = spyClient.getTaskInformation("vertex_1468518877269_3815_1_01", "task_1468518877269_3815_1_01_000002", 2);

    TezCountersProto taskCountersProto = TezCountersProto.newBuilder()
      .addCounterGroups(TezCounterGroupProto.newBuilder()
        .setDisplayName("group1")
        .setName("group1")
        .addCounters(TezCounterProto.newBuilder()
          .setDisplayName("c1")
          .setName("c1")
          .setValue(1)))
      .build();

    TaskInformationProto taskInformationProto1 = TaskInformationProto.newBuilder()
      .setId("task_1468518877269_3815_1_01_000002")
      .setDiagnostics("sample diagnostics")
      .setStartTime(1470778337429L)
      .setState(TaskStateProto.TASK_SUCCEEDED)
      .setScheduledTime(1470778337429L)
      .setEndTime(1470778368101L)
      .setSuccessfulAttemptId("attempt_1468518877269_3815_1_01_000002_0")
      .setTaskCounters(taskCountersProto)
      .build();

    List<TaskInformation> expectedTaskInformations = Lists.newArrayList(new TaskInformation(taskInformationProto1));
    Assert.assertEquals(expectedTaskInformations, taskInformationList);
  }
}
