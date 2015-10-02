package org.cloudfoundry.community.servicebroker.brooklyn.service.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import org.apache.brooklyn.rest.domain.CatalogItemSummary;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.text.Strings;
import org.apache.brooklyn.util.yaml.Yamls;
import org.cloudfoundry.community.servicebroker.brooklyn.config.BrooklynConfig;
import org.cloudfoundry.community.servicebroker.brooklyn.service.BrooklynRestAdmin;
import org.cloudfoundry.community.servicebroker.brooklyn.service.ServiceUtil;
import org.cloudfoundry.community.servicebroker.model.DashboardClient;
import org.cloudfoundry.community.servicebroker.model.Plan;
import org.cloudfoundry.community.servicebroker.model.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

public abstract class AbstractCatalogPlanStrategy implements CatalogPlanStrategy{
	
	private static final Logger LOG = LoggerFactory.getLogger(AbstractCatalogPlanStrategy.class);
	
	private BrooklynRestAdmin admin;
	private PlaceholderReplacer replacer;
    private BrooklynConfig config;
	
	@Autowired
	public AbstractCatalogPlanStrategy(BrooklynRestAdmin admin, PlaceholderReplacer replacer, BrooklynConfig config) {
		this.admin = admin;
		this.replacer = replacer;
        this.config = config;
	}
	
	protected BrooklynRestAdmin getAdmin() {
		return admin;
	}
	
	protected PlaceholderReplacer replacer(){
		return replacer;
	}
	
	@Override
	public List<ServiceDefinition> makeServiceDefinitions() {
		Future<List<CatalogItemSummary>> pageFuture = admin.getCatalogApplications(config.includesAllCatalogVersions());
		Map<String, ServiceDefinition> definitions = new HashMap<>();
        Set<String> names = Sets.newHashSet();

        List<CatalogItemSummary> page = ServiceUtil.getFutureValueLoggingError(pageFuture);
        for (CatalogItemSummary app : page) {
			try {
				String id = app.getSymbolicName();
                String name;
                LOG.info("Brooklyn Application={}", app);
                if(config.includesAllCatalogVersions()) {
                    id = app.getId();
                    name = ServiceUtil.getSafeName("br_" + app.getName() + "_" + app.getVersion());
                } else {
                    name = ServiceUtil.getUniqueName("br_" + app.getName(), names);
                }
				
				String description = app.getDescription();
				boolean bindable = true;
				boolean planUpdatable = false;
				List<Plan> plans = new ArrayList<>();
				try {
                    Iterable<Object> planYaml = Yamls.parseAll(app.getPlanYaml());
                    Object rootElement = Iterables.getOnlyElement(planYaml);
                    if (!isHidden(rootElement)) {
                        plans = makePlans(id, rootElement);
                    }
				} catch(Exception e) {
					LOG.error("unable to make plans: Unexpected blueprint format");
				}
				if (plans.isEmpty()) {
					continue;
				}
				List<String> tags = getTags();
				String iconUrl = null;
				if (!Strings.isEmpty(app.getIconUrl())) {
					Future<String> iconAsBase64Future = admin.getIconAsBase64(app.getIconUrl());
					iconUrl = ServiceUtil.getFutureValueLoggingError(iconAsBase64Future);
				}
				Map<String, Object> metadata = getServiceDefinitionMetadata(iconUrl, app.getPlanYaml());
				List<String> requires = getTags();
				DashboardClient dashboardClient = null;
                definitions.put(id, new ServiceDefinition(id, name, description,
                        bindable, planUpdatable, plans, tags, metadata,
                        requires, dashboardClient));
			} catch (Exception e) {
				LOG.error("unable to add catalog item: {}", e.getMessage());
			}
		}
        return new ArrayList<>(definitions.values());
	}

    private boolean isHidden(Object rootElement) {
        Maybe<Map<String, Object>> maybeBrokerConfig = getBrokerConfig(rootElement);
        if (maybeBrokerConfig.isAbsent()) {
            return false;
        }
        return Boolean.TRUE.equals(maybeBrokerConfig.get().get("hidden"));
    }

    protected Maybe<Map<String, Object>> getBrokerConfig(Object rootElement) {
        if (rootElement == null) {
            return Maybe.absent();
        }
        if (rootElement instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) rootElement;
            if (map.containsKey("brooklyn.config")) {
                Map<String, Object> brooklynConfig = (Map<String, Object>)map.get("brooklyn.config");
                if (brooklynConfig != null && brooklynConfig.containsKey("broker.config")) {
                    Map<String, Object> brokerConfig = (Map<String, Object>)brooklynConfig.get("broker.config");
                    if (brokerConfig != null) {
                        return Maybe.of(brokerConfig);
                    }
                }
            }
        }
        return Maybe.absent();
    }

    private List<String> getTags() {
		return Arrays.asList();
	}

	private Map<String, Object> getServiceDefinitionMetadata(String iconUrl, String planYaml) {
		Map<String, Object> metadata = new HashMap<>();
        if (iconUrl != null) metadata.put("imageUrl", iconUrl);
        metadata.put("planYaml", planYaml);
		return metadata;
	}

}
