package com.outbrain.ob1k.cache.memcache.folsom;

import com.outbrain.ob1k.consul.HealthInfoInstance;
import com.outbrain.ob1k.consul.HealthyTargetsList;
import com.spotify.dns.DnsSrvResolver;
import com.spotify.dns.LookupResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A {@link DnsSrvResolver} that looks up consul rather than DNS.
 *
 * @author Eran Harel
 */
public class HealthyMemcachedResolver implements DnsSrvResolver, HealthyTargetsList.TargetsChangedListener {

  private static final Logger log = LoggerFactory.getLogger(HealthyMemcachedResolver.class);

  private static final int NODE_PRIORITY = 1;
  private static final int NODE_WEIGHT = 1;
  private static final int LOOKUP_TTL_SEC = 15;

  private final HealthyTargetsList healthyTargetsList;

  private volatile List<LookupResult> resolvedCluster;

  public HealthyMemcachedResolver(final HealthyTargetsList healthyTargetsList) {
    this.healthyTargetsList = Objects.requireNonNull(healthyTargetsList, "healthyTargetsList must not be null");
    healthyTargetsList.addListener(this);
  }

  @Override
  public List<LookupResult> resolve(final String fqdn_ignored) {
    return resolvedCluster;
  }

  @Override
  public void onTargetsChanged(final List<HealthInfoInstance> healthTargets) {
    resolvedCluster = healthTargets.stream()
      .map(this::toLookupResult)
      .collect(Collectors.toList());
    log.info("New {} cluster was resolved: {}", healthyTargetsList.getModule(), resolvedCluster);
  }

  private LookupResult toLookupResult(final HealthInfoInstance instance) {
    return LookupResult.create(instance.Node.Address, (int)instance.Service.Port, NODE_PRIORITY, NODE_WEIGHT, LOOKUP_TTL_SEC);
  }

  public List<LookupResult> getResolvedCluster() {
    return Collections.unmodifiableList(resolvedCluster);
  }
}
