/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.metrics.source;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;

import java.io.IOException;

import org.neo4j.cypher.PlanCacheMetricsMonitor;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.metrics.MetricsSettings;

import static com.codahale.metrics.MetricRegistry.name;

public class CypherMetrics extends LifecycleAdapter
{
    private static final String NAME_PREFIX = "neo4j.cypher";
    public static final String REPLAN_EVENTS = name( NAME_PREFIX, "replan_events" );

    private final Config config;
    private final Monitors monitors;
    private final MetricRegistry registry;
    private final PlanCacheMetricsMonitor cacheMonitor = new PlanCacheMetricsMonitor();

    public CypherMetrics( Config config, Monitors monitors, MetricRegistry registry )
    {
        this.config = config;
        this.monitors = monitors;
        this.registry = registry;
    }

    @Override
    public void start() throws Throwable
    {
        if ( config.get( MetricsSettings.cypherPlanningEnabled ) )
        {
            monitors.addMonitorListener( cacheMonitor );

            registry.register( REPLAN_EVENTS, new Gauge<Long>()
            {
                @Override
                public Long getValue()
                {
                    return cacheMonitor.numberOfReplans();
                }
            } );
        }
    }

    @Override
    public void stop() throws IOException
    {
        if ( config.get( MetricsSettings.cypherPlanningEnabled ) )
        {
            registry.remove( REPLAN_EVENTS );

            monitors.removeMonitorListener( cacheMonitor );
        }
    }
}

