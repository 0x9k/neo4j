/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;

import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.query.QuerySource;
import org.neo4j.storageengine.api.lock.ResourceType;
import org.neo4j.storageengine.api.lock.WaitStrategy;
import org.neo4j.test.FakeCpuClock;
import org.neo4j.time.Clocks;
import org.neo4j.time.FakeClock;

import static java.util.Collections.singletonMap;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class ExecutingQueryTest
{
    private final FakeClock clock = Clocks.fakeClock( ZonedDateTime.parse( "2016-12-03T15:10:00+01:00" ) );
    private final FakeCpuClock cpuClock = new FakeCpuClock();
    private ExecutingQuery query = new ExecutingQuery(
            1,
            new QuerySource(),
            "neo4j",
            "hello world",
            Collections.emptyMap(),
            Collections.emptyMap(),
            Thread.currentThread(),
            clock,
            cpuClock );

    @Test
    public void shouldReportElapsedTime() throws Exception
    {
        // when
        clock.forward( 10, TimeUnit.SECONDS );
        long elapsedTime = query.elapsedTime();

        // then
        assertEquals( 10_000, elapsedTime );
    }

    @Test
    public void shouldReportWaitTime() throws Exception
    {
        // initial
        assertEquals( singletonMap( "state", "RUNNING" ), query.status() );

        // when
        clock.forward( 10, TimeUnit.SECONDS );
        try ( Locks.WaitEvent event = lock( "NODE", 17 ) )
        {
            clock.forward( 5, TimeUnit.SECONDS );

            // then
            assertThat( query.status(), CoreMatchers.<Map<String,Object>>allOf(
                    hasEntry( "state", "WAITING" ),
                    hasEntry( "time", 5_000L ),
                    hasEntry( "resourceType", "NODE" ),
                    hasEntry( equalTo( "resourceIds" ), longArray( 17 ) ) ) );
            assertEquals( 5_000, query.waitTime() );
        }
        assertEquals( singletonMap( "state", "RUNNING" ), query.status() );
        assertEquals( 5_000, query.waitTime() );

        // when
        clock.forward( 2, TimeUnit.SECONDS );
        try ( Locks.WaitEvent event = lock( "RELATIONSHIP", 612 ) )
        {
            clock.forward( 1, TimeUnit.SECONDS );

            // then
            assertThat( query.status(), CoreMatchers.<Map<String,Object>>allOf(
                    hasEntry( "state", "WAITING" ),
                    hasEntry( "time", 1_000L ),
                    hasEntry( "resourceType", "RELATIONSHIP" ),
                    hasEntry( equalTo( "resourceIds" ), longArray( 612 ) ) ) );
            assertEquals( 6_000, query.waitTime() );
        }
        assertEquals( singletonMap( "state", "RUNNING" ), query.status() );
        assertEquals( 6_000, query.waitTime() );
    }

    @Test
    public void shouldReportCpuTime() throws Exception
    {
        // given
        cpuClock.add( 60, TimeUnit.MILLISECONDS );

        // when
        long cpuTime = query.cpuTime();

        // then
        assertEquals( 60_000_000, cpuTime );
    }

    private Locks.WaitEvent lock( String resourceType, long resourceId )
    {
        return query.lockTracer().waitForLock( resourceType( resourceType ), resourceId );
    }

    static ResourceType resourceType( String string )
    {
        return new ResourceType()
        {
            @Override
            public String toString()
            {
                return string;
            }

            @Override
            public int typeId()
            {
                throw new UnsupportedOperationException( "not used" );
            }

            @Override
            public WaitStrategy waitStrategy()
            {
                throw new UnsupportedOperationException( "not used" );
            }
        };
    }

    @SuppressWarnings( "unchecked" )
    private static Matcher<Object> longArray( long... expected )
    {
        return (Matcher) new TypeSafeMatcher<long[]>()
        {
            @Override
            protected boolean matchesSafely( long[] item )
            {
                return Arrays.equals( expected, item );
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendValue( expected );
            }
        };
    }
}
