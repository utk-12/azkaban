/*
 * Copyright 2021 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.executor.container;

import static azkaban.Constants.JobProperties.USER_TO_PROXY;

import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableFlowBase;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutorManagerException;
import azkaban.executor.Status;
import azkaban.flow.Flow;
import azkaban.project.Project;
import azkaban.project.ProjectManager;
import azkaban.utils.Props;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import org.apache.commons.codec.digest.MurmurHash3;

/**
 * Utility class containing static methods to be used during Containerized Dispatch
 */
public class ContainerImplUtils {

  private ContainerImplUtils() {
    // Not to be instantiated
  }

  /**
   * This method is used to get jobTypes for a flow. This method is going to call
   * populateJobTypeForFlow which has recursive method call to traverse the DAG for a flow.
   *
   * @param flow Executable flow object
   * @return
   * @throws ExecutorManagerException
   */
  public static TreeSet<String> getJobTypesForFlow(final ExecutableFlow flow) {
    final TreeSet<String> jobTypes = new TreeSet<>();
    populateJobTypeForFlow(flow, jobTypes);
    return jobTypes;
  }

  /**
   * This method is used to populate jobTypes for ExecutableNode.
   *
   * @param node
   * @param jobTypes
   */
  private static void populateJobTypeForFlow(final ExecutableNode node, Set<String> jobTypes) {
    if (node instanceof ExecutableFlowBase) {
      final ExecutableFlowBase base = (ExecutableFlowBase) node;
      for (ExecutableNode subNode : base.getExecutableNodes()) {
        populateJobTypeForFlow(subNode, jobTypes);
      }
    } else {
      // If a node is disabled, we don't need to initialize its jobType container image when
      // creating a pod.
      if (node.getStatus() != Status.DISABLED) {
        jobTypes.add(node.getType());
      }
    }
  }

  /**
   * Return the int mapping between 1 to 100 for a given flow name
   * @param flow
   * @return
   */
  public static int getFlowNameHashValMapping(ExecutableFlow flow) {
    // Flow name is <projectName>.<flowId>
    String flowName = flow.getFlowName();
    byte[] flowNameBytes = flowName.getBytes(StandardCharsets.UTF_8);
    // To be utilized for flow deterministic ramp-up
    int flowNameHashVal = Math.abs(MurmurHash3.hash32x86(flowNameBytes));
    return flowNameHashVal % 100 + 1;
  }

  public static HashSet<String> getProxyUsersForFlow(final ProjectManager projectManager,
      final ExecutableFlow flow) {
    final HashSet<String> proxyUsers = new HashSet<>();
    populateProxyUsersForFlow(flow, flow, projectManager, proxyUsers);
    return proxyUsers;
  }

  public static void populateProxyUsersForFlow(final ExecutableFlow flow,
      final ExecutableNode node, final ProjectManager projectManager, HashSet<String> proxyUsers) {
    if (node instanceof ExecutableFlowBase) {
      final ExecutableFlowBase base = (ExecutableFlowBase) node;
      for (ExecutableNode subNode : base.getExecutableNodes()) {
        populateProxyUsersForFlow(flow, subNode, projectManager, proxyUsers);
      }
    } else {
      // If a node is disabled, we don't need to initialize its jobType container image when
      // creating a pod.
      if (node.getStatus() != Status.DISABLED) {
        Project project = projectManager.getProject(flow.getProjectId());
        Flow flowObj = project.getFlow(flow.getFlowId());
        Props currentNodeProps = projectManager.getProperties(project, flowObj,
            node.getId(), node.getJobSource());
        // Get the node level property for proxy user.
        String userToProxyFromNode = currentNodeProps.getString(USER_TO_PROXY, null);
        // Get the node level override by user from the UI for proxy user.
        Props currentNodeJobProps = projectManager.getJobOverrideProperty(project, flowObj,
            node.getId(), node.getJobSource());
        String userToProxyFromJobNode = currentNodeJobProps.getString(USER_TO_PROXY, null);
        if (userToProxyFromJobNode != null) {
          proxyUsers.add(userToProxyFromJobNode);
        } else if (userToProxyFromNode != null) {
          proxyUsers.add(userToProxyFromNode);
        }
      }
    }
  }

  // Extract the proxy users needed from  PREFETCH_JOBTYPE_PROXY_USER_MAP
  public static HashSet<String> getJobTypeUsersForFlow(String jobTypePrefetchUserMap,
      TreeSet<String> jobTypes) {
    HashSet<String> jobTypeProxyUserSet = new HashSet<>();
    StringTokenizer st = new StringTokenizer(jobTypePrefetchUserMap, ";");
    while (st.hasMoreTokens()) {
      StringTokenizer stInner = new StringTokenizer(st.nextToken(), ",");
      String jobType = stInner.nextToken();
      String jobTypeUser = stInner.nextToken();
      if (jobTypes.contains(jobType)) {
        jobTypeProxyUserSet.add(jobTypeUser);
      }
    }
    return jobTypeProxyUserSet;
  }
}