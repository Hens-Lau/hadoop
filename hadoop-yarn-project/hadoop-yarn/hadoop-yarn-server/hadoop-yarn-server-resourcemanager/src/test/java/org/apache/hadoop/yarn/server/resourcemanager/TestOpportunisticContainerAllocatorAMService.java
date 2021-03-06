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

package org.apache.hadoop.yarn.server.resourcemanager;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocolPB;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.AllocateRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.AllocateResponsePBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.FinishApplicationMasterRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.FinishApplicationMasterResponsePBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.RegisterApplicationMasterRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.RegisterApplicationMasterResponsePBImpl;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ExecutionTypeRequest;
import org.apache.hadoop.yarn.api.records.ExecutionType;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.server.api.DistributedSchedulingAMProtocolPB;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.server.api.protocolrecords.DistributedSchedulingAllocateRequest;
import org.apache.hadoop.yarn.server.api.protocolrecords.DistributedSchedulingAllocateResponse;
import org.apache.hadoop.yarn.server.api.protocolrecords.RegisterDistributedSchedulingAMResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;

import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.HadoopYarnProtoRPC;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.server.api.protocolrecords.RemoteNode;
import org.apache.hadoop.yarn.server.api.protocolrecords.impl.pb.DistributedSchedulingAllocateRequestPBImpl;
import org.apache.hadoop.yarn.server.api.protocolrecords.impl.pb.DistributedSchedulingAllocateResponsePBImpl;
import org.apache.hadoop.yarn.server.api.protocolrecords.impl.pb.RegisterDistributedSchedulingAMResponsePBImpl;
import org.apache.hadoop.yarn.server.api.records.OpportunisticContainersStatus;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.AMLivelinessMonitor;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacityScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacitySchedulerConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeUpdateSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.security.RMContainerTokenSecretManager;
import org.apache.hadoop.yarn.server.scheduler.OpportunisticContainerContext;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

/**
 * Test cases for {@link OpportunisticContainerAllocatorAMService}.
 */
public class TestOpportunisticContainerAllocatorAMService {

  private static final int GB = 1024;

  @Test(timeout = 60000)
  public void testNodeRemovalDuringAllocate() throws Exception {
    CapacitySchedulerConfiguration csConf =
        new CapacitySchedulerConfiguration();
    YarnConfiguration conf = new YarnConfiguration(csConf);
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);
    conf.setBoolean(
        YarnConfiguration.OPPORTUNISTIC_CONTAINER_ALLOCATION_ENABLED, true);
    conf.setInt(
        YarnConfiguration.NM_CONTAINER_QUEUING_SORTING_NODES_INTERVAL_MS, 100);
    MockRM rm = new MockRM(conf);
    rm.start();
    MockNM nm1 = new MockNM("h1:1234", 4096, rm.getResourceTrackerService());
    MockNM nm2 = new MockNM("h2:1234", 4096, rm.getResourceTrackerService());
    nm1.registerNode();
    nm2.registerNode();
    OpportunisticContainerAllocatorAMService amservice =
        (OpportunisticContainerAllocatorAMService) rm
            .getApplicationMasterService();
    RMApp app1 = rm.submitApp(1 * GB, "app", "user", null, "default");
    ApplicationAttemptId attemptId =
        app1.getCurrentAppAttempt().getAppAttemptId();
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm, nm2);
    ResourceScheduler scheduler = rm.getResourceScheduler();
    RMNode rmNode1 = rm.getRMContext().getRMNodes().get(nm1.getNodeId());
    RMNode rmNode2 = rm.getRMContext().getRMNodes().get(nm2.getNodeId());
    nm1.nodeHeartbeat(true);
    nm2.nodeHeartbeat(true);
    ((RMNodeImpl) rmNode1)
        .setOpportunisticContainersStatus(getOppurtunisticStatus(-1, 100));
    ((RMNodeImpl) rmNode2)
        .setOpportunisticContainersStatus(getOppurtunisticStatus(-1, 100));
    OpportunisticContainerContext ctxt = ((CapacityScheduler) scheduler)
        .getApplicationAttempt(attemptId).getOpportunisticContainerContext();
    // Send add and update node events to AM Service.
    amservice.handle(new NodeAddedSchedulerEvent(rmNode1));
    amservice.handle(new NodeAddedSchedulerEvent(rmNode2));
    amservice.handle(new NodeUpdateSchedulerEvent(rmNode1));
    amservice.handle(new NodeUpdateSchedulerEvent(rmNode2));
    // Both node 1 and node 2 will be applicable for scheduling.
    for (int i = 0; i < 10; i++) {
      am1.allocate(
          Arrays.asList(ResourceRequest.newInstance(Priority.newInstance(1),
              "*", Resources.createResource(1 * GB), 2)),
          null);
      if (ctxt.getNodeMap().size() == 2) {
        break;
      }
      Thread.sleep(50);
    }
    Assert.assertEquals(2, ctxt.getNodeMap().size());
    // Remove node from scheduler but not from AM Service.
    scheduler.handle(new NodeRemovedSchedulerEvent(rmNode1));
    // After removal of node 1, only 1 node will be applicable for scheduling.
    for (int i = 0; i < 10; i++) {
      try {
        am1.allocate(
            Arrays.asList(ResourceRequest.newInstance(Priority.newInstance(1),
                "*", Resources.createResource(1 * GB), 2)),
            null);
      } catch (Exception e) {
        Assert.fail("Allocate request should be handled on node removal");
      }
      if (ctxt.getNodeMap().size() == 1) {
        break;
      }
      Thread.sleep(50);
    }
    Assert.assertEquals(1, ctxt.getNodeMap().size());
  }

  private OpportunisticContainersStatus getOppurtunisticStatus(int waitTime,
      int queueLength) {
    OpportunisticContainersStatus status1 =
        Mockito.mock(OpportunisticContainersStatus.class);
    Mockito.when(status1.getEstimatedQueueWaitTime()).thenReturn(waitTime);
    Mockito.when(status1.getWaitQueueLength()).thenReturn(queueLength);
    return status1;
  }

  // Test if the OpportunisticContainerAllocatorAMService can handle both
  // DSProtocol as well as AMProtocol clients
  @Test
  public void testRPCWrapping() throws Exception {
    Configuration conf = new Configuration();
    conf.set(YarnConfiguration.IPC_RPC_IMPL, HadoopYarnProtoRPC.class
        .getName());
    YarnRPC rpc = YarnRPC.create(conf);
    String bindAddr = "localhost:0";
    InetSocketAddress addr = NetUtils.createSocketAddr(bindAddr);
    conf.setSocketAddr(YarnConfiguration.RM_SCHEDULER_ADDRESS, addr);
    final RecordFactory factory = RecordFactoryProvider.getRecordFactory(null);
    final RMContext rmContext = new RMContextImpl() {
      @Override
      public AMLivelinessMonitor getAMLivelinessMonitor() {
        return null;
      }

      @Override
      public Configuration getYarnConfiguration() {
        return new YarnConfiguration();
      }

      @Override
      public RMContainerTokenSecretManager getContainerTokenSecretManager() {
        return new RMContainerTokenSecretManager(conf);
      }
    };
    Container c = factory.newRecordInstance(Container.class);
    c.setExecutionType(ExecutionType.OPPORTUNISTIC);
    c.setId(
        ContainerId.newContainerId(
            ApplicationAttemptId.newInstance(
                ApplicationId.newInstance(12345, 1), 2), 3));
    AllocateRequest allReq =
        (AllocateRequestPBImpl)factory.newRecordInstance(AllocateRequest.class);
    allReq.setAskList(Arrays.asList(
        ResourceRequest.newInstance(Priority.UNDEFINED, "a",
            Resource.newInstance(1, 2), 1, true, "exp",
            ExecutionTypeRequest.newInstance(
                ExecutionType.OPPORTUNISTIC, true))));
    OpportunisticContainerAllocatorAMService service =
        createService(factory, rmContext, c);
    conf.setBoolean(YarnConfiguration.DIST_SCHEDULING_ENABLED, true);
    Server server = service.getServer(rpc, conf, addr, null);
    server.start();

    // Verify that the OpportunisticContainerAllocatorAMSercvice can handle
    // vanilla ApplicationMasterProtocol clients
    RPC.setProtocolEngine(conf, ApplicationMasterProtocolPB.class,
        ProtobufRpcEngine.class);
    ApplicationMasterProtocolPB ampProxy =
        RPC.getProxy(ApplicationMasterProtocolPB
            .class, 1, NetUtils.getConnectAddress(server), conf);
    RegisterApplicationMasterResponse regResp =
        new RegisterApplicationMasterResponsePBImpl(
            ampProxy.registerApplicationMaster(null,
                ((RegisterApplicationMasterRequestPBImpl)factory
                    .newRecordInstance(
                        RegisterApplicationMasterRequest.class)).getProto()));
    Assert.assertEquals("dummyQueue", regResp.getQueue());
    FinishApplicationMasterResponse finishResp =
        new FinishApplicationMasterResponsePBImpl(
            ampProxy.finishApplicationMaster(null,
                ((FinishApplicationMasterRequestPBImpl)factory
                    .newRecordInstance(
                        FinishApplicationMasterRequest.class)).getProto()
            ));
    Assert.assertEquals(false, finishResp.getIsUnregistered());
    AllocateResponse allocResp =
        new AllocateResponsePBImpl(
            ampProxy.allocate(null,
                ((AllocateRequestPBImpl)factory
                    .newRecordInstance(AllocateRequest.class)).getProto())
        );
    List<Container> allocatedContainers = allocResp.getAllocatedContainers();
    Assert.assertEquals(1, allocatedContainers.size());
    Assert.assertEquals(ExecutionType.OPPORTUNISTIC,
        allocatedContainers.get(0).getExecutionType());
    Assert.assertEquals(12345, allocResp.getNumClusterNodes());


    // Verify that the DistrubutedSchedulingService can handle the
    // DistributedSchedulingAMProtocol clients as well
    RPC.setProtocolEngine(conf, DistributedSchedulingAMProtocolPB.class,
        ProtobufRpcEngine.class);
    DistributedSchedulingAMProtocolPB dsProxy =
        RPC.getProxy(DistributedSchedulingAMProtocolPB
            .class, 1, NetUtils.getConnectAddress(server), conf);

    RegisterDistributedSchedulingAMResponse dsRegResp =
        new RegisterDistributedSchedulingAMResponsePBImpl(
            dsProxy.registerApplicationMasterForDistributedScheduling(null,
                ((RegisterApplicationMasterRequestPBImpl)factory
                    .newRecordInstance(RegisterApplicationMasterRequest.class))
                    .getProto()));
    Assert.assertEquals(54321l, dsRegResp.getContainerIdStart());
    Assert.assertEquals(4,
        dsRegResp.getMaxContainerResource().getVirtualCores());
    Assert.assertEquals(1024,
        dsRegResp.getMinContainerResource().getMemorySize());
    Assert.assertEquals(2,
        dsRegResp.getIncrContainerResource().getVirtualCores());

    DistributedSchedulingAllocateRequestPBImpl distAllReq =
        (DistributedSchedulingAllocateRequestPBImpl)factory.newRecordInstance(
            DistributedSchedulingAllocateRequest.class);
    distAllReq.setAllocateRequest(allReq);
    distAllReq.setAllocatedContainers(Arrays.asList(c));
    DistributedSchedulingAllocateResponse dsAllocResp =
        new DistributedSchedulingAllocateResponsePBImpl(
            dsProxy.allocateForDistributedScheduling(null,
                distAllReq.getProto()));
    Assert.assertEquals(
        "h1", dsAllocResp.getNodesForScheduling().get(0).getNodeId().getHost());

    FinishApplicationMasterResponse dsfinishResp =
        new FinishApplicationMasterResponsePBImpl(
            dsProxy.finishApplicationMaster(null,
                ((FinishApplicationMasterRequestPBImpl) factory
                    .newRecordInstance(FinishApplicationMasterRequest.class))
                    .getProto()));
    Assert.assertEquals(
        false, dsfinishResp.getIsUnregistered());
  }

  private OpportunisticContainerAllocatorAMService createService(
      final RecordFactory factory, final RMContext rmContext,
      final Container c) {
    return new OpportunisticContainerAllocatorAMService(rmContext, null) {
      @Override
      public RegisterApplicationMasterResponse registerApplicationMaster(
          RegisterApplicationMasterRequest request) throws
          YarnException, IOException {
        RegisterApplicationMasterResponse resp = factory.newRecordInstance(
            RegisterApplicationMasterResponse.class);
        // Dummy Entry to Assert that we get this object back
        resp.setQueue("dummyQueue");
        return resp;
      }

      @Override
      public FinishApplicationMasterResponse finishApplicationMaster(
          FinishApplicationMasterRequest request) throws YarnException,
          IOException {
        FinishApplicationMasterResponse resp = factory.newRecordInstance(
            FinishApplicationMasterResponse.class);
        // Dummy Entry to Assert that we get this object back
        resp.setIsUnregistered(false);
        return resp;
      }

      @Override
      public AllocateResponse allocate(AllocateRequest request) throws
          YarnException, IOException {
        AllocateResponse response = factory.newRecordInstance(
            AllocateResponse.class);
        response.setNumClusterNodes(12345);
        response.setAllocatedContainers(Arrays.asList(c));
        return response;
      }

      @Override
      public RegisterDistributedSchedulingAMResponse
          registerApplicationMasterForDistributedScheduling(
          RegisterApplicationMasterRequest request)
          throws YarnException, IOException {
        RegisterDistributedSchedulingAMResponse resp = factory
            .newRecordInstance(RegisterDistributedSchedulingAMResponse.class);
        resp.setContainerIdStart(54321L);
        resp.setMaxContainerResource(Resource.newInstance(4096, 4));
        resp.setMinContainerResource(Resource.newInstance(1024, 1));
        resp.setIncrContainerResource(Resource.newInstance(2048, 2));
        return resp;
      }

      @Override
      public DistributedSchedulingAllocateResponse
          allocateForDistributedScheduling(
          DistributedSchedulingAllocateRequest request)
          throws YarnException, IOException {
        List<ResourceRequest> askList =
            request.getAllocateRequest().getAskList();
        List<Container> allocatedContainers = request.getAllocatedContainers();
        Assert.assertEquals(1, allocatedContainers.size());
        Assert.assertEquals(ExecutionType.OPPORTUNISTIC,
            allocatedContainers.get(0).getExecutionType());
        Assert.assertEquals(1, askList.size());
        Assert.assertTrue(askList.get(0)
            .getExecutionTypeRequest().getEnforceExecutionType());
        DistributedSchedulingAllocateResponse resp = factory
            .newRecordInstance(DistributedSchedulingAllocateResponse.class);
        resp.setNodesForScheduling(
            Arrays.asList(RemoteNode.newInstance(
                NodeId.newInstance("h1", 1234), "http://h1:4321")));
        return resp;
      }
    };
  }
}
