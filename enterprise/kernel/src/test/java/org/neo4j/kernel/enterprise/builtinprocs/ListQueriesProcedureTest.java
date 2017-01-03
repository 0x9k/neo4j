/*
 * Copyright (c) 2002-2016 "Neo Technology,"
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
package org.neo4j.kernel.enterprise.builtinprocs;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.Rule;
import org.junit.Test;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.enterprise.ImpermanentEnterpriseDatabaseRule;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.concurrent.ThreadingRule;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.neo4j.test.rule.concurrent.ThreadingRule.waitingWhileIn;

public class ListQueriesProcedureTest
{
    @Rule
    public final DatabaseRule db = new ImpermanentEnterpriseDatabaseRule();
    @Rule
    public final ThreadingRule threads = new ThreadingRule();

    @Test
    public void shouldContainTheQueryItself() throws Exception
    {
        // given
        String query = "CALL dbms.listQueries";

        // when
        Result result = db.execute( query );

        // then
        Map<String,Object> row = result.next();
        assertFalse( result.hasNext() );
        //        Object queryId = row1.get( "queryId" );
        //        Object username = row1.get( "username" );
        //        Object query = row1.get( "query" );
        //        Object parameters = row1.get( "parameters" );
        //        Object startTime = row1.get( "startTime" );
        //        Object elapsedTime = row1.get( "elapsedTime" );
        //        Object connectionDetails = row1.get( "connectionDetails" );
        //        Object metaData = row1.get( "metaData" );
        assertEquals( query, row.get( "query" ) );
    }

    @Test
    public void shouldProvideElapsedCpuTime() throws Exception
    {
        // given
        CountDownLatch nodeLocked = new CountDownLatch( 1 ), listQueriesLatch = new CountDownLatch( 1 );
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            node.setProperty( "v", 1L );
            tx.success();
        }
        threads.execute( parameter ->
        {
            try ( Transaction tx = db.beginTx() )
            {
                tx.acquireWriteLock( node );
                nodeLocked.countDown();
                listQueriesLatch.await();
            }
            return null;
        }, null );
        nodeLocked.await();

        threads.executeAndAwait( parameter ->
        {
            db.execute( "MATCH (n) SET n.v = n.v + 1" ).close();
            return null;
        }, null, waitingWhileIn( GraphDatabaseFacade.class, "execute" ), 5, SECONDS );

        try
        {
            // when
            Map<String,Object> data = getQueryListing( "MATCH (n) SET n.v = n.v + 1" );

            // then
            assertTrue( "should contain a 'cpuTime' field", data.containsKey( "cpuTime" ) );
            Object cpuTime1 = data.get( "cpuTime" );
            assertThat( cpuTime1, instanceOf( Long.class ) );
            assertTrue( "should contain a 'status' field", data.containsKey( "status" ) );
            Object status = data.get( "status" );
            assertThat( status, instanceOf( Map.class ) );
            @SuppressWarnings( "unchecked" )
            Map<String,Object> statusMap = (Map<String,Object>) status;
            assertEquals( "WAITING", statusMap.get( "state" ) );
            assertEquals( "NODE", statusMap.get( "resourceType" ) );
            assertArrayEquals( new long[]{node.getId()}, (long[]) statusMap.get( "resourceIds" ) );
            assertTrue( "should contain a 'waitTime' field", data.containsKey( "waitTime" ) );
            Object waitTime1 = data.get( "waitTime" );
            assertThat( waitTime1, instanceOf( Long.class ) );

            // when
            data = getQueryListing( "MATCH (n) SET n.v = n.v + 1" );

            // then
            Long cpuTime2 = (Long) data.get( "cpuTime" );
            assertThat( cpuTime2, greaterThan( (Long) cpuTime1 ) );
            Long waitTime2 = (Long) data.get( "waitTime" );
            assertThat( waitTime2, greaterThan( (Long) waitTime1 ) );
        }
        finally
        {
            listQueriesLatch.countDown();
        }
    }

    private Map<String,Object> getQueryListing( String query )
    {
        try ( Result rows = db.execute( "CALL dbms.listQueries" ) )
        {
            while ( rows.hasNext() )
            {
                Map<String,Object> row = rows.next();
                if ( query.equals( row.get( "query" ) ) )
                {
                    return row;
                }
            }
        }
        throw new AssertionError( "query not active: " + query );
    }
}
