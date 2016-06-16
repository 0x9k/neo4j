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
package org.neo4j.coreedge.raft.replication.shipping;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.neo4j.coreedge.catchup.storecopy.LocalDatabase;
import org.neo4j.coreedge.raft.LeaderContext;
import org.neo4j.coreedge.raft.OutboundMessageCollector;
import org.neo4j.coreedge.raft.RaftMessages;
import org.neo4j.coreedge.raft.RaftMessages.AppendEntries;
import org.neo4j.coreedge.raft.ReplicatedInteger;
import org.neo4j.coreedge.raft.ReplicatedString;
import org.neo4j.coreedge.raft.log.DelegatingRaftLog;
import org.neo4j.coreedge.raft.log.InMemoryRaftLog;
import org.neo4j.coreedge.raft.log.RaftLog;
import org.neo4j.coreedge.raft.log.RaftLogEntry;
import org.neo4j.coreedge.raft.log.segmented.InFlightMap;
import org.neo4j.coreedge.server.RaftTestMember;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.impl.store.StoreId;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.test.DoubleLatch;
import org.neo4j.test.matchers.Matchers;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.neo4j.test.matchers.Matchers.hasMessage;
import static org.neo4j.test.matchers.Matchers.hasRaftLogEntries;

public class RaftLogShipperTest
{
    private OutboundMessageCollector outbound;
    private RaftLog raftLog;
    private Clock clock;
    private RaftTestMember leader;
    private RaftTestMember follower;
    private long leaderTerm;
    private long leaderCommit;
    private long retryTimeMillis;
    private int catchupBatchSize = 64;
    private int maxAllowedShippingLag = 256;
    private LogProvider logProvider;
    private Log log;

    private RaftLogShipper<RaftTestMember> logShipper;

    private RaftLogEntry entry0 = new RaftLogEntry( 0, ReplicatedInteger.valueOf( 1000 ) );
    private RaftLogEntry entry1 = new RaftLogEntry( 0, ReplicatedString.valueOf( "kedha" ) );
    private RaftLogEntry entry2 = new RaftLogEntry( 0, ReplicatedInteger.valueOf( 2000 ) );
    private RaftLogEntry entry3 = new RaftLogEntry( 0, ReplicatedString.valueOf( "chupchick" ) );
    private StoreId storeId = new StoreId( 1, 2, 3, 4, 5 );

    @Before
    public void setup()
    {
        // defaults
        outbound = new OutboundMessageCollector();
        raftLog = new InMemoryRaftLog();
        clock = Clock.systemUTC();
        leader = new RaftTestMember( 0 );
        follower = new RaftTestMember( 1 );
        leaderTerm = 0;
        leaderCommit = 0;
        retryTimeMillis = 100000;
        logProvider = mock( LogProvider.class );
        log = mock( Log.class );
        when( logProvider.getLog( RaftLogShipper.class ) ).thenReturn( log );
    }

    @After
    public void teardown()
    {
        if ( logShipper != null )
        {
            logShipper.stop();
            logShipper = null;
        }
    }

    private void startLogShipper()
    {
        LocalDatabase localDatabase = mock( LocalDatabase.class );
        when( localDatabase.storeId() ).thenReturn( storeId );

        logShipper =
                new RaftLogShipper<>( outbound, logProvider, raftLog, clock, leader, follower, leaderTerm, leaderCommit,
                        retryTimeMillis, catchupBatchSize, maxAllowedShippingLag, new InFlightMap<>()
                );
        logShipper.start();
    }

    @Test
    public void shouldSendLastEntryOnStart() throws Throwable
    {
        // given
        raftLog.append( entry0 );
        raftLog.append( entry1 );

        // when
        startLogShipper();

        // then
        assertThat( outbound.sentTo( follower ), Matchers.<RaftTestMember>hasRaftLogEntries( singletonList( entry1 ) ) );
    }

    @Test
    public void shouldSendPreviousEntryOnMismatch() throws Throwable
    {
        // given
        raftLog.append( entry0 );
        raftLog.append( entry1 );
        startLogShipper();

        // when
        outbound.clear();
        logShipper.onMismatch( 0, new LeaderContext( 0, 0 ) );

        // then
        assertThat( outbound.sentTo( follower ), Matchers.<RaftTestMember>hasRaftLogEntries( singletonList( entry0 ) ) );
    }

    @Test
    public void shouldKeepSendingFirstEntryAfterSeveralMismatches() throws Throwable
    {
        // given
        raftLog.append( entry0 );
        raftLog.append( entry1 );
        startLogShipper();

        logShipper.onMismatch( 0, new LeaderContext( 0, 0 ) );
        logShipper.onMismatch( 0, new LeaderContext( 0, 0 ) );

        // when
        outbound.clear();
        logShipper.onMismatch( 0, new LeaderContext( 0, 0 ) );

        // then
        assertTrue( outbound.hasEntriesTo( follower, entry0 ) );
        assertThat( outbound.sentTo( follower ), Matchers.<RaftTestMember>hasRaftLogEntries( singletonList( entry0 ) ) );
    }

    @Test
    public void shouldSendNextBatchAfterMatch() throws Throwable
    {
        // given
        raftLog.append( entry0 );
        raftLog.append( entry1 );
        raftLog.append( entry2 );
        raftLog.append( entry3 );
        startLogShipper();

        logShipper.onMismatch( 0, new LeaderContext( 0, 0 ) );

        // when
        outbound.clear();
        logShipper.onMatch( 0, new LeaderContext( 0, 0 ) );

        // then
        assertThat( outbound.sentTo( follower ), Matchers.<RaftTestMember>hasRaftLogEntries( asList( entry1, entry2, entry3 ) ) );
    }

    @Test
    public void shouldSendNewEntriesAfterMatchingLastEntry() throws Throwable
    {
        // given
        raftLog.append( entry0 );
        startLogShipper();

        logShipper.onMatch( 0, new LeaderContext( 0, 0 ) );

        // when
        outbound.clear();

        raftLog.append( entry1 );
        logShipper.onNewEntries( 0, 0, new RaftLogEntry[]{entry1}, new LeaderContext( 0, 0 ) );
        raftLog.append( entry2 );
        logShipper.onNewEntries( 1, 0, new RaftLogEntry[]{entry2}, new LeaderContext( 0, 0 ) );

        // then
        assertThat( outbound.sentTo( follower ), Matchers.<RaftTestMember>hasRaftLogEntries( asList( entry1, entry2 ) ) );
    }

    @Test
    public void shouldNotSendNewEntriesWhenNotMatched() throws Throwable
    {
        // given
        raftLog.append( entry0 );
        startLogShipper();

        // when
        outbound.clear();
        logShipper.onNewEntries( 0, 0, new RaftLogEntry[]{entry1}, new LeaderContext( 0, 0 ) );
        logShipper.onNewEntries( 1, 0, new RaftLogEntry[]{entry2}, new LeaderContext( 0, 0 ) );

        // then
        assertEquals( outbound.sentTo( follower ).size(), 0 );
    }

    @Test
    public void shouldResendLastSentEntryOnFirstMismatch() throws Throwable
    {
        // given
        raftLog.append( entry0 );
        startLogShipper();
        raftLog.append( entry1 );
        raftLog.append( entry2 );

        logShipper.onMatch( 0, new LeaderContext( 0, 0 ) );
        logShipper.onNewEntries( 0, 0, new RaftLogEntry[]{entry1}, new LeaderContext( 0, 0 ) );
        logShipper.onNewEntries( 1, 0, new RaftLogEntry[]{entry2}, new LeaderContext( 0, 0 ) );

        // when
        outbound.clear();
        logShipper.onMismatch( 1, new LeaderContext( 0, 0 ) );

        // then
        assertThat( outbound.sentTo( follower ), Matchers.<RaftTestMember>hasRaftLogEntries( singletonList( entry2 ) ) );
    }

    @Test
    public void shouldSendAllEntriesAndCatchupCompletely() throws Throwable
    {
        // given
        final int ENTRY_COUNT = catchupBatchSize * 10;
        Collection<RaftLogEntry> entries = new ArrayList<>();
        for ( int i = 0; i < ENTRY_COUNT; i++ )
        {
            entries.add( new RaftLogEntry( 0, ReplicatedInteger.valueOf( i ) ) );
        }

        for ( RaftLogEntry entry : entries )
        {
            raftLog.append( entry );
        }

        // then
        startLogShipper();

        // back-tracking stage
        RaftLogEntry firstEntry = new RaftLogEntry( 0, ReplicatedInteger.valueOf( 0 ) );
        while ( !outbound.hasEntriesTo( follower, firstEntry ) )
        {
            logShipper.onMismatch( -1, new LeaderContext( 0, 0 ) );
        }

        // catchup stage
        long matchIndex;

        do
        {
            AppendEntries.Request last = (AppendEntries.Request) Iterables.last( outbound.sentTo( follower ) );
            matchIndex = last.prevLogIndex() + last.entries().length;

            outbound.clear();
            logShipper.onMatch( matchIndex, new LeaderContext( 0, 0 ) );
        }
        while ( outbound.sentTo( follower ).size() > 0 );

        assertEquals( ENTRY_COUNT - 1, matchIndex );
    }

    @Test
    public void shouldSendMostRecentlyAvailableEntryIfPruningHappened() throws IOException
    {
        //given
        raftLog.append( entry0 );
        raftLog.append( entry1 );
        raftLog.append( entry2 );
        raftLog.append( entry3 );

        startLogShipper();

        //when
        raftLog.prune( 2 );
        outbound.clear();
        logShipper.onMismatch( 0, new LeaderContext( 0, 0 ) );

        //then
        assertTrue( outbound.hasAnyEntriesTo( follower ) );
        assertThat( outbound.sentTo( follower ), Matchers.<RaftTestMember>hasRaftLogEntries( singletonList( entry3 ) ) );
    }

    @Test
    public void shouldSendLogCompactionInfoToFollowerOnMatchIfEntryHasBeenPrunedAway() throws Exception
    {
        //given
        AtomicBoolean afterInit = new AtomicBoolean();
        final DoubleLatch latch = new DoubleLatch();
        raftLog = new DelegatingRaftLog( raftLog )
        {
            @Override
            public long readEntryTerm( long logIndex ) throws IOException
            {
                if ( afterInit.get() )
                {
                    latch.start();
                    latch.awaitFinish();
                }
                return super.readEntryTerm( logIndex );
            }
        };

        raftLog.append( entry0 );
        raftLog.append( entry1 );
        raftLog.append( entry2 );
        raftLog.append( entry3 );

        startLogShipper();

        afterInit.set( true );

        //when
        outbound.clear();
        Thread pruningThread = new Thread( "Pruning" )
        {
            @Override
            public void run()
            {
                try
                {
                    latch.awaitStart();
                    raftLog.prune( 2 );
                    latch.finish();
                }
                catch ( IOException e )
                {
                    throw new RuntimeException( e );
                }
            }
        };

        pruningThread.start();
        logShipper.onMatch( 1, new LeaderContext( 0, 0 ) );

        //then
        assertTrue( outbound.hasAnyEntriesTo( follower ) );
        assertThat( outbound.sentTo( follower ),
                hasMessage( new RaftMessages.LogCompactionInfo<>( leader, 0, 1 ) ) );

        pruningThread.join();
    }

}
