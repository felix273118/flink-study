/*
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

package org.apache.flink.kubernetes.kubeclient.parameters;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.utils.KubernetesUtils;
import org.apache.flink.runtime.clusterframework.ContaineredTaskManagerParameters;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A utility class helps parse, verify, and manage the Kubernetes side parameters
 * that are used for constructing the TaskManager Pod.
 */
public class KubernetesTaskManagerParameters extends AbstractKubernetesParameters {

	public static final String TASK_MANAGER_MAIN_CONTAINER_NAME = "flink-task-manager";

	private final String podName;

	private final String dynamicProperties;

	private final ContaineredTaskManagerParameters containeredTaskManagerParameters;

	private final Map<String, Long> taskManagerExternalResources;

	public KubernetesTaskManagerParameters(
			Configuration flinkConfig,
			String podName,
			String dynamicProperties,
			ContaineredTaskManagerParameters containeredTaskManagerParameters,
			Map<String, Long> taskManagerExternalResources) {
		super(flinkConfig);
		this.podName = checkNotNull(podName);
		this.dynamicProperties = checkNotNull(dynamicProperties);
		this.containeredTaskManagerParameters = checkNotNull(containeredTaskManagerParameters);
		this.taskManagerExternalResources = checkNotNull(taskManagerExternalResources);
	}

	@Override
	public Map<String, String> getLabels() {
		final Map<String, String> labels = new HashMap<>();
		labels.putAll(flinkConfig.getOptional(KubernetesConfigOptions.TASK_MANAGER_LABELS).orElse(Collections.emptyMap()));
		labels.putAll(KubernetesUtils.getTaskManagerLabels(getClusterId()));
		return Collections.unmodifiableMap(labels);
	}

	@Override
	public Map<String, String> getNodeSelector() {
		return Collections.unmodifiableMap(
			flinkConfig.getOptional(KubernetesConfigOptions.TASK_MANAGER_NODE_SELECTOR).orElse(Collections.emptyMap()));
	}

	@Override
	public Map<String, String> getEnvironments() {
		return this.containeredTaskManagerParameters.taskManagerEnv();
	}

	@Override
	public Map<String, String> getAnnotations() {
		return flinkConfig.getOptional(KubernetesConfigOptions.TASK_MANAGER_ANNOTATIONS).orElse(Collections.emptyMap());
	}

	@Override
	public List<Map<String, String>> getTolerations() {
		return flinkConfig.getOptional(KubernetesConfigOptions.TASK_MANAGER_TOLERATIONS).orElse(Collections.emptyList());
	}

	public String getTaskManagerMainContainerName() {
		return TASK_MANAGER_MAIN_CONTAINER_NAME;
	}

	public String getPodName() {
		return podName;
	}

	public int getTaskManagerMemoryMB() {
		return containeredTaskManagerParameters.getTaskExecutorProcessSpec().getTotalProcessMemorySize().getMebiBytes();
	}

	public double getTaskManagerCPU() {
		return containeredTaskManagerParameters.getTaskExecutorProcessSpec().getCpuCores().getValue().doubleValue();
	}

	public double getTaskManagerCPURequestFactor() {
		final double requestFactor =
			flinkConfig.getDouble(KubernetesConfigOptions.TASK_MANAGER_CPU_REQUEST_FACTOR);
		checkArgument(
			requestFactor <= 1,
			"%s should be less than or equal to 1.",
			KubernetesConfigOptions.TASK_MANAGER_CPU_REQUEST_FACTOR.key());
		return requestFactor;
	}

	public double getTaskManagerMemoryRequestFactor() {
		final double requestFactor =
			flinkConfig.getDouble(KubernetesConfigOptions.TASK_MANAGER_MEMORY_REQUEST_FACTOR);
		checkArgument(
			requestFactor <= 1,
			"%s should be less than or equal to 1.",
			KubernetesConfigOptions.TASK_MANAGER_MEMORY_REQUEST_FACTOR.key());
		return requestFactor;
	}

	public String getServiceAccount() {
		return flinkConfig.getOptional(KubernetesConfigOptions.TASK_MANAGER_SERVICE_ACCOUNT)
			.orElse(flinkConfig.getString(KubernetesConfigOptions.KUBERNETES_SERVICE_ACCOUNT));

	}

	public Map<String, Long> getTaskManagerExternalResources() {
		return taskManagerExternalResources;
	}

	public int getRPCPort() {
		final int taskManagerRpcPort = KubernetesUtils.parsePort(flinkConfig, TaskManagerOptions.RPC_PORT);
		checkArgument(taskManagerRpcPort > 0, "%s should not be 0.", TaskManagerOptions.RPC_PORT.key());
		return taskManagerRpcPort;
	}

	public String getDynamicProperties() {
		return dynamicProperties;
	}

	public ContaineredTaskManagerParameters getContaineredTaskManagerParameters() {
		return containeredTaskManagerParameters;
	}
}
