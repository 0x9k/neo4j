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
package org.neo4j.coreedge.raft.state.vote;

import java.io.IOException;

import org.neo4j.coreedge.raft.state.ChannelMarshal;
import org.neo4j.coreedge.raft.state.StateMarshal;
import org.neo4j.coreedge.server.CoreMember;
import org.neo4j.storageengine.api.ReadPastEndException;
import org.neo4j.storageengine.api.ReadableChannel;
import org.neo4j.storageengine.api.WritableChannel;

public class VoteState
{
    private CoreMember votedFor;
    private long term = -1;

    public VoteState()
    {
    }

    public VoteState( CoreMember votedFor, long term )
    {
        this.term = term;
        this.votedFor = votedFor;
    }

    public CoreMember votedFor()
    {
        return votedFor;
    }

    public boolean update( CoreMember votedFor, long term )
    {
        if ( termChanged( term ) )
        {
            this.votedFor = votedFor;
            this.term = term;
            return true;
        }
        else
        {
            if ( this.votedFor == null )
            {
                if ( votedFor != null )
                {
                    this.votedFor = votedFor;
                    return true;
                }
            }
            else if ( !this.votedFor.equals( votedFor ) )
            {
                throw new IllegalArgumentException( "Can only vote once per term." );
            }
            return false;
        }
    }

    private boolean termChanged( long term )
    {
        return term != this.term;
    }

    public long term()
    {
        return term;
    }

    public static class Marshal implements StateMarshal<VoteState>
    {
        private final ChannelMarshal<CoreMember> memberMarshal;

        public Marshal( ChannelMarshal<CoreMember> memberMarshal )
        {
            this.memberMarshal = memberMarshal;
        }

        @Override
        public void marshal( VoteState state, WritableChannel channel ) throws IOException
        {
            channel.putLong( state.term );
            memberMarshal.marshal( state.votedFor(), channel );
        }

        @Override
        public VoteState unmarshal( ReadableChannel source ) throws IOException
        {
            try
            {
                final long term = source.getLong();
                final CoreMember member = memberMarshal.unmarshal( source );

                if ( member == null )
                {
                    return null;
                }

                return new VoteState( member, term );
            }
            catch ( ReadPastEndException notEnoughBytes )
            {
                return null;
            }
        }

        @Override
        public VoteState startState()
        {
            return new VoteState();
        }

        @Override
        public long ordinal( VoteState state )
        {
            return state.term();
        }
    }
}
