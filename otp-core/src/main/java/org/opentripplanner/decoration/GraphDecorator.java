/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.decoration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.services.EmbeddedConfigService;
import org.opentripplanner.updater.PeriodicTimerGraphUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorate a graph upon loading through Preferences (Preference is the new Java API replacing
 * "Properties"). Usually preferences are loaded from a .properties files, but could also come from
 * the graph itself or any other sources.
 * 
 * When a graph is loaded, client should call setupGraph() with the Preferences setup.
 * 
 * When a graph is unloaded, one must ensure the shutdownGraph() method is called to cleanup all
 * resources that could have been created.
 * 
 * This class then create "beans" (usually real-time connector, etc...) depending on the given
 * configuration, and configure them using the corresponding children Preferences node.
 * 
 * If an embedded configuration is present in the graph, we also try to use it. In case of conflicts
 * between two child nodes in both configs (two childs node with the same name) the dynamic (ie
 * provided) configuration takes complete precedence over the embedded one: childrens properties are
 * *not* merged.
 * 
 */
public class GraphDecorator {

    private static Logger LOG = LoggerFactory.getLogger(GraphDecorator.class);

    private static Map<String, Class<? extends Configurable>> configurables;

    static {
        configurables = new HashMap<String, Class<? extends Configurable>>();
        configurables.put("bike-rental", BikeRentalDecorator.class);
        configurables.put("stop-time-updater", StopTimeUpdateDecorator.class);
        configurables.put("real-time-alerts", RealTimeAlertDecorator.class);
    }

    public void setupGraph(Graph graph, Preferences mainConfig) {
        // Create a periodic updater per graph
        PeriodicTimerGraphUpdater periodicUpdater = graph.getService(
                PeriodicTimerGraphUpdater.class, true);

        // Look for embedded config if it exists
        EmbeddedConfigService embeddedConfigService = graph.getService(EmbeddedConfigService.class);
        Preferences embeddedConfig = null;
        if (embeddedConfigService != null) {
            embeddedConfig = new PropertiesPreferences(embeddedConfigService.getProperties());
        }
        LOG.info("Using configurations: " + (mainConfig == null ? "" : "[main]") + " "
                + (embeddedConfig == null ? "" : "[embedded]"));
        applyConfigurationToGraph(graph, Arrays.asList(mainConfig, embeddedConfig));

        // Delete the periodic updater if it contains nothing
        if (periodicUpdater.size() == 0) {
            graph.putService(PeriodicTimerGraphUpdater.class, null);
        }
    }

    /**
     * Apply a list of configs to a graph. Please note that the order of the config in the list *is
     * important* as a child node already seen will not be overriden.
     */
    private void applyConfigurationToGraph(Graph graph, List<Preferences> configs) {
        try {
            Set<String> beanNames = new HashSet<String>();
            for (Preferences config : configs) {
                if (config == null)
                    continue;
                for (String beanName : config.childrenNames()) {
                    if (beanNames.contains(beanName))
                        continue; // Already processed
                    beanNames.add(beanName);
                    Preferences beanConfig = config.node(beanName);
                    String beanType = beanConfig.get("type", null);
                    Class<? extends Configurable> clazz = configurables.get(beanType);
                    if (clazz != null) {
                        try {
                            LOG.info("Configuring bean '{}' of type '{}' ({})", beanName, beanType,
                                    clazz.getName());
                            Configurable bean = clazz.newInstance();
                            bean.configure(graph, beanConfig);
                        } catch (Exception e) {
                            LOG.error("Can't configure bean: " + beanName, e);
                            // Continue on next bean
                        }
                    }
                }
            }
        } catch (BackingStoreException e) {
            LOG.error("Can't read configuration", e); // Should not happen
        }
    }

    public void shutdownGraph(Graph graph) {
        ShutdownGraphService shutdownGraphService = graph.getService(ShutdownGraphService.class);
        if (shutdownGraphService != null) {
            shutdownGraphService.shutdown(graph);
        }
        PeriodicTimerGraphUpdater periodicUpdater = graph
                .getService(PeriodicTimerGraphUpdater.class);
        if (periodicUpdater != null) {
            LOG.info("Stopping periodic updater with " + periodicUpdater.size() + " updaters.");
            periodicUpdater.stop();
        }
    }
}
