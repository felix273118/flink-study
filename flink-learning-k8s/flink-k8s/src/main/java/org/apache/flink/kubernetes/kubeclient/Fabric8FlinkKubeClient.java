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

package org.apache.flink.kubernetes.kubeclient;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.configuration.KubernetesConfigOptions;
import org.apache.flink.kubernetes.configuration.KubernetesLeaderElectionConfiguration;
import org.apache.flink.kubernetes.kubeclient.decorators.ExternalServiceDecorator;
import org.apache.flink.kubernetes.kubeclient.decorators.InternalServiceDecorator;
import org.apache.flink.kubernetes.kubeclient.resources.*;
import org.apache.flink.kubernetes.utils.Constants;
import org.apache.flink.kubernetes.utils.KubernetesUtils;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.ExecutorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * The implementation of {@link FlinkKubeClient}.
 */
public class Fabric8FlinkKubeClient implements FlinkKubeClient {

	private static final Logger LOG = LoggerFactory.getLogger(Fabric8FlinkKubeClient.class);

	private final KubernetesClient internalClient;
	private final String clusterId;
	private final String namespace;
	private final int maxRetryAttempts;

	private final ExecutorService kubeClientExecutorService;

	// save the master deployment atomic reference for setting owner reference of task manager pods
	private final AtomicReference<Deployment> masterDeploymentRef;


	public Fabric8FlinkKubeClient(
			Configuration flinkConfig,
			KubernetesClient client,
			ExecutorService executorService) {
		this.internalClient = checkNotNull(client);
		this.clusterId = flinkConfig
							.getOptional(KubernetesConfigOptions.CLUSTER_ID)
							.orElseThrow(
								() ->
									new IllegalArgumentException(
										String.format(
											"Configuration option '%s' is not set.",
											KubernetesConfigOptions.CLUSTER_ID.key())));

		this.namespace = flinkConfig.getString(KubernetesConfigOptions.NAMESPACE);

		this.maxRetryAttempts = flinkConfig.getInteger(
			KubernetesConfigOptions.KUBERNETES_TRANSACTIONAL_OPERATION_MAX_RETRIES);

		this.kubeClientExecutorService = checkNotNull(executorService);
		this.masterDeploymentRef = new AtomicReference<>();
	}

	@Override
	public void createJobManagerComponent(KubernetesJobManagerSpecification kubernetesJMSpec) {
		final Deployment deployment = kubernetesJMSpec.getDeployment();
		final List<HasMetadata> accompanyingResources = kubernetesJMSpec.getAccompanyingResources();

		// create Deployment
		LOG.debug(
			"Start to create deployment with spec {}{}",
			System.lineSeparator(),
			KubernetesUtils.tryToGetPrettyPrintYaml(deployment));
		final Deployment createdDeployment = this.internalClient
			.apps()
			.deployments()
			.inNamespace(this.namespace)
			.create(deployment);

		// Note that we should use the uid of the created Deployment for the OwnerReference.
		setOwnerReference(createdDeployment, accompanyingResources);

		this.internalClient
			.resourceList(accompanyingResources)
			.inNamespace(this.namespace)
			.createOrReplace();
	}

	@Override
	public CompletableFuture<Void> createTaskManagerPod(KubernetesPod kubernetesPod) {
		return CompletableFuture.runAsync(
			() -> {
				if (masterDeploymentRef.get() == null) {
					final Deployment masterDeployment =
						this.internalClient
							.apps()
							.deployments()
							.withName(KubernetesUtils.getDeploymentName(clusterId))
							.get();
					if (masterDeployment == null) {
						throw new RuntimeException(
							"Failed to find Deployment named "
								+ clusterId
								+ " in namespace "
								+ this.namespace);
					}
					masterDeploymentRef.compareAndSet(null, masterDeployment);
				}

				// Note that we should use the uid of the master Deployment for the OwnerReference.
				setOwnerReference(masterDeploymentRef.get(), Collections.singletonList(kubernetesPod.getInternalResource()));

				LOG.debug(
					"Start to create pod with spec {}{}",
					System.lineSeparator(),
					KubernetesUtils.tryToGetPrettyPrintYaml(
						kubernetesPod.getInternalResource()));

				this.internalClient
					.pods()
					.inNamespace(this.namespace)
					.create(kubernetesPod.getInternalResource());
				},
			kubeClientExecutorService);
	}

	@Override
	public CompletableFuture<Void> stopPod(String podName) {
		return CompletableFuture.runAsync(
			() -> this.internalClient.pods().withName(podName).delete(),
			kubeClientExecutorService);
	}

	@Override
	public Optional<Endpoint> getRestEndpoint(String clusterId) {
		Optional<KubernetesService> restService =
			getService(KubernetesService.ServiceType.REST_SERVICE, clusterId);
		if (!restService.isPresent()) {
			return Optional.empty();
		}
		final Service service = restService.get().getInternalResource();
		final int restPort = getRestPortFromExternalService(service);

		final KubernetesConfigOptions.ServiceExposedType serviceExposedType =
			KubernetesConfigOptions.ServiceExposedType.valueOf(service.getSpec().getType());

		// Return the external service.namespace directly when using ClusterIP.
		if (serviceExposedType == KubernetesConfigOptions.ServiceExposedType.ClusterIP) {
			return Optional.of(
				new Endpoint(ExternalServiceDecorator.getNamespacedExternalServiceName(clusterId, namespace), restPort));
		}

		return getRestEndPointFromService(service, restPort);
	}

	@Override
	public List<KubernetesPod> getPodsWithLabels(Map<String, String> labels) {
		final List<Pod> podList = this.internalClient.pods().withLabels(labels).list().getItems();

		if (podList == null || podList.isEmpty()) {
			return new ArrayList<>();
		}

		return podList
			.stream()
			.map(KubernetesPod::new)
			.collect(Collectors.toList());
	}

	@Override
	public void stopAndCleanupCluster(String clusterId) {
		this.internalClient
			.apps()
			.deployments()
			.inNamespace(this.namespace)
			.withName(KubernetesUtils.getDeploymentName(clusterId))
			.cascading(true)
			.delete();
	}

	@Override
	public void handleException(Exception e) {
		LOG.error("A Kubernetes exception occurred.", e);
	}

	@Override
	public Optional<KubernetesService> getService(
		KubernetesService.ServiceType serviceType, String clusterId) {
		final String serviceName = getServiceName(serviceType, clusterId);

		final Service service = this.internalClient
			.services()
			.inNamespace(namespace)
			.withName(serviceName)
			.fromServer()
			.get();

		if (service == null) {
			LOG.debug("Service {} does not exist", serviceName);
			return Optional.empty();
		}

		return Optional.of(new KubernetesService(service));
	}

	@Override
	public KubernetesWatch watchPodsAndDoCallback(
			Map<String, String> labels,
			WatchCallbackHandler<KubernetesPod> podCallbackHandler) {
		return new KubernetesWatch(
			this.internalClient.pods()
				.withLabels(labels)
				.watch(new KubernetesPodsWatcher(podCallbackHandler)));
	}

	@Override
	public KubernetesLeaderElector createLeaderElector(
			KubernetesLeaderElectionConfiguration leaderElectionConfiguration,
			KubernetesLeaderElector.LeaderCallbackHandler leaderCallbackHandler) {
		return new KubernetesLeaderElector(
			(NamespacedKubernetesClient) this.internalClient,
			namespace,
			leaderElectionConfiguration,
			leaderCallbackHandler);
	}

	@Override
	public CompletableFuture<Void> createConfigMap(KubernetesConfigMap configMap) {
		final String configMapName = configMap.getName();
		return CompletableFuture.runAsync(
			() -> this.internalClient.configMaps().inNamespace(namespace).create(configMap.getInternalResource()),
			kubeClientExecutorService)
			.exceptionally(
				throwable -> {
					throw new CompletionException(
						new KubernetesException("Failed to create ConfigMap " + configMapName, throwable));
				});
	}

	@Override
	public Optional<KubernetesConfigMap> getConfigMap(String name) {
		final ConfigMap configMap = this.internalClient.configMaps().inNamespace(namespace).withName(name).get();
		return configMap == null ? Optional.empty() : Optional.of(new KubernetesConfigMap(configMap));
	}

	@Override
	public CompletableFuture<Boolean> checkAndUpdateConfigMap(
			String configMapName,
			Function<KubernetesConfigMap, Optional<KubernetesConfigMap>> function) {
		return FutureUtils.retry(
			() -> CompletableFuture.supplyAsync(
				() -> getConfigMap(configMapName)
					.map(
						configMap -> function.apply(configMap).map(
							updatedConfigMap -> {
								try {
									this.internalClient.configMaps()
										.inNamespace(namespace)
										.withName(configMapName)
										.lockResourceVersion(updatedConfigMap.getResourceVersion())
										.replace(updatedConfigMap.getInternalResource());
								} catch (Throwable throwable) {
									LOG.debug("Failed to update ConfigMap {} with data {} because of concurrent " +
										"modifications. Trying again.", configMap.getName(), configMap.getData());
									throw throwable;
								}
								return true;
							}).orElse(false))
					.orElseThrow(() -> new CompletionException(
						new KubernetesException("Cannot retry checkAndUpdateConfigMap with configMap "
							+ configMapName + " because it does not exist."))),
				kubeClientExecutorService),
			maxRetryAttempts,
			// Only KubernetesClientException is retryable
			throwable -> ExceptionUtils.findThrowable(throwable, KubernetesClientException.class).isPresent(),
			kubeClientExecutorService);
	}

	@Override
	public KubernetesWatch watchConfigMaps(
			String name,
			WatchCallbackHandler<KubernetesConfigMap> callbackHandler) {
		return new KubernetesWatch(
			this.internalClient.configMaps().withName(name).watch(new KubernetesConfigMapWatcher(callbackHandler)));
	}

	@Override
	public CompletableFuture<Void> deleteConfigMapsByLabels(Map<String, String> labels) {
		return CompletableFuture.runAsync(
			() -> this.internalClient.configMaps().inNamespace(namespace).withLabels(labels).delete(),
			kubeClientExecutorService);
	}

	@Override
	public CompletableFuture<Void> deleteConfigMap(String configMapName) {
		return CompletableFuture.runAsync(
			() -> this.internalClient.configMaps().inNamespace(namespace).withName(configMapName).delete(),
			kubeClientExecutorService);
	}

	@Override
	public void close() {
		this.internalClient.close();
		ExecutorUtils.gracefulShutdown(5, TimeUnit.SECONDS, this.kubeClientExecutorService);
	}

	@Override
	public CompletableFuture<Void> updateServiceTargetPort(
		KubernetesService.ServiceType serviceType,
		String clusterId,
		String portName,
		int targetPort) {
		LOG.debug("Update {} target port to {}", portName, targetPort);
		return CompletableFuture.runAsync(
			() ->
				getService(serviceType, clusterId)
					.ifPresent(
						service -> {
							final Service updatedService =
								new ServiceBuilder(
									service.getInternalResource())
									.editSpec()
									.editMatchingPort(
										servicePortBuilder ->
											servicePortBuilder
												.build()
												.getName()
												.equals(
													portName))
									.withTargetPort(
										new IntOrString(targetPort))
									.endPort()
									.endSpec()
									.build();
							this.internalClient
								.services()
								.withName(
									getServiceName(serviceType, clusterId))
								.replace(updatedService);
						}),
			kubeClientExecutorService);
	}

	/**
	 * Get the Kubernetes service name.
	 *
	 * @param serviceType The service type
	 * @param clusterId The cluster id
	 * @return Return the Kubernetes service name if the service type is known.
	 */
	private String getServiceName(KubernetesService.ServiceType serviceType, String clusterId) {
		switch (serviceType) {
			case REST_SERVICE:
				return ExternalServiceDecorator.getExternalServiceName(clusterId);
			case INTERNAL_SERVICE:
				return InternalServiceDecorator.getInternalServiceName(clusterId);
			default:
				throw new IllegalArgumentException(
					"Unrecognized service type: " + serviceType.name());
		}
	}


	private void setOwnerReference(Deployment deployment, List<HasMetadata> resources) {
		final OwnerReference deploymentOwnerReference = new OwnerReferenceBuilder()
			.withName(deployment.getMetadata().getName())
			.withApiVersion(deployment.getApiVersion())
			.withUid(deployment.getMetadata().getUid())
			.withKind(deployment.getKind())
			.withController(true)
			.withBlockOwnerDeletion(true)
			.build();
		resources.forEach(resource ->
			resource.getMetadata().setOwnerReferences(Collections.singletonList(deploymentOwnerReference)));
	}

	/**
	 * Get rest port from the external Service.
	 */
	private int getRestPortFromExternalService(Service externalService) {
		final List<ServicePort> servicePortCandidates = externalService.getSpec().getPorts()
			.stream()
			.filter(x -> x.getName().equals(Constants.REST_PORT_NAME))
			.collect(Collectors.toList());

		if (servicePortCandidates.isEmpty()) {
			throw new RuntimeException("Failed to find port \"" + Constants.REST_PORT_NAME + "\" in Service \"" +
				ExternalServiceDecorator.getExternalServiceName(this.clusterId) + "\"");
		}

		final ServicePort externalServicePort = servicePortCandidates.get(0);

		final KubernetesConfigOptions.ServiceExposedType externalServiceType =
			KubernetesConfigOptions.ServiceExposedType.valueOf(externalService.getSpec().getType());

		switch (externalServiceType) {
			case ClusterIP:
			case LoadBalancer:
				return externalServicePort.getPort();
			case NodePort:
				return externalServicePort.getNodePort();
			default:
				throw new RuntimeException("Unrecognized Service type: " + externalServiceType);
		}
	}

	private Optional<Endpoint> getRestEndPointFromService(Service service, int restPort) {
		if (service.getStatus() == null) {
			return Optional.empty();
		}

		LoadBalancerStatus loadBalancer = service.getStatus().getLoadBalancer();
		boolean hasExternalIP = service.getSpec() != null &&
			service.getSpec().getExternalIPs() != null && !service.getSpec().getExternalIPs().isEmpty();

		if (loadBalancer != null) {
			return getLoadBalancerRestEndpoint(loadBalancer, restPort);
		} else if (hasExternalIP) {
			final String address = service.getSpec().getExternalIPs().get(0);
			if (address != null && !address.isEmpty()) {
				return Optional.of(new Endpoint(address, restPort));
			}
		}
		return Optional.empty();
	}

	private Optional<Endpoint> getLoadBalancerRestEndpoint(LoadBalancerStatus loadBalancer, int restPort) {
		boolean hasIngress = loadBalancer.getIngress() != null && !loadBalancer.getIngress().isEmpty();
		String address;
		if (hasIngress) {
			address = loadBalancer.getIngress().get(0).getIp();
			// Use hostname when the ip address is null
			if (address == null || address.isEmpty()) {
				address = loadBalancer.getIngress().get(0).getHostname();
			}
		} else {
			// Use node port
			address = this.internalClient.getMasterUrl().getHost();
		}
		boolean noAddress = address == null || address.isEmpty();
		return noAddress ? Optional.empty() : Optional.of(new Endpoint(address, restPort));
	}
}
