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
package org.neo4j.server.security.auth;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.neo4j.kernel.api.security.exception.IllegalCredentialsException;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.server.security.auth.exception.ConcurrentModificationException;

/** A user repository implementation that just stores users in memory */
public class InMemoryUserRepository extends LifecycleAdapter implements UserRepository
{
    private final ConcurrentHashMap<String, User> users = new ConcurrentHashMap<>();

    @Override
    public User getUserByName( String username )
    {
        return users.get( username );
    }

    @Override
    public void create( User user ) throws IllegalCredentialsException
    {
        synchronized (this)
        {
            // Check for existing user or token
            for ( User other : users.values() )
            {
                if ( other.name().equals( user.name() ) )
                {
                    throw new IllegalCredentialsException( "The specified user already exists" );
                }
            }

            users.put( user.name(), user );
        }
    }

    @Override
    public void update( User existingUser, User updatedUser ) throws ConcurrentModificationException
    {
        // Assert input is ok
        if ( !existingUser.name().equals( updatedUser.name() ) )
        {
            throw new IllegalArgumentException( "updatedUser has a different name" );
        }

        synchronized (this)
        {
            boolean foundUser = false;
            for ( User other : users.values() )
            {
                if ( other.equals( existingUser ) )
                {
                    foundUser = true;
                }
            }

            if ( !foundUser )
            {
                throw new ConcurrentModificationException();
            }

            users.put( updatedUser.name(), updatedUser );
        }
    }

    @Override
    public boolean delete( User user )
    {
        synchronized (this)
        {
            return users.remove( user.name() ) != null;
        }
    }

    @Override
    public int numberOfUsers()
    {
        return users.size();
    }

    @Override
    public boolean isValidUsername( String username )
    {
        // This repo can store any name
        return true;
    }

    @Override
    public Set<String> getAllUsernames()
    {
        return users.values().stream().map( User::name ).collect( Collectors.toSet() );
    }
}
