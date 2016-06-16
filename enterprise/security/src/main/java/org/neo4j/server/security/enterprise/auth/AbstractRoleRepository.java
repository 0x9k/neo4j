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
package org.neo4j.server.security.enterprise.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.server.security.auth.exception.ConcurrentModificationException;

public abstract class AbstractRoleRepository extends LifecycleAdapter implements RoleRepository
{
    // TODO: We could improve concurrency by using a ReadWriteLock

    /** Quick lookup of roles by name */
    protected final Map<String,RoleRecord> rolesByName = new ConcurrentHashMap<>();
    private final Map<String,SortedSet<String>> rolesByUsername = new ConcurrentHashMap<>();

    /** Master list of roles */
    protected volatile List<RoleRecord> roles = new ArrayList<>();

    @Override
    public RoleRecord findByName( String name )
    {
        return rolesByName.get( name );
    }

    @Override
    public Set<String> findRoleNamesByUsername( String username )
    {
        Set<String> roleNames = rolesByUsername.get( username );
        return roleNames != null ? roleNames : Collections.emptySet();
    }

    @Override
    public void create( RoleRecord role ) throws IllegalArgumentException, IOException
    {
        if ( !isValidName( role.name() ) )
        {
            throw new IllegalArgumentException( "'" + role.name() + "' is not a valid role name." );
        }

        synchronized ( this )
        {
            // Check for existing role
            for ( RoleRecord other : roles )
            {
                if ( other.name().equals( role.name() ) )
                {
                    throw new IllegalArgumentException( "The specified role already exists" );
                }
            }

            roles.add( role );

            saveRoles();

            rolesByName.put( role.name(), role );

            populateUserMap( role );
        }
    }

    @Override
    public void update( RoleRecord existingRole, RoleRecord updatedRole )
            throws ConcurrentModificationException, IOException
    {
        // Assert input is ok
        if ( !existingRole.name().equals( updatedRole.name() ) )
        {
            throw new IllegalArgumentException( "updated role has a different name" );
        }

        synchronized ( this )
        {
            // Copy-on-write for the roles list
            List<RoleRecord> newRoles = new ArrayList<>();
            boolean foundRole = false;
            for ( RoleRecord other : roles )
            {
                if ( other.equals( existingRole ) )
                {
                    foundRole = true;
                    newRoles.add( updatedRole );
                }
                else
                {
                    newRoles.add( other );
                }
            }

            if ( !foundRole )
            {
                throw new ConcurrentModificationException();
            }

            roles = newRoles;

            saveRoles();

            rolesByName.put( updatedRole.name(), updatedRole );

            removeFromUserMap( existingRole );
            populateUserMap( updatedRole );
        }
    }

    @Override
    public boolean delete( RoleRecord role ) throws IOException
    {
        boolean foundRole = false;
        synchronized ( this )
        {
            // Copy-on-write for the roles list
            List<RoleRecord> newRoles = new ArrayList<>();
            for ( RoleRecord other : roles )
            {
                if ( other.name().equals( role.name() ) )
                {
                    foundRole = true;
                }
                else
                {
                    newRoles.add( other );
                }
            }

            if ( foundRole )
            {
                roles = newRoles;

                saveRoles();

                rolesByName.remove( role.name() );
            }

            removeFromUserMap( role );
        }
        return foundRole;
    }

    /**
     * Override this in the implementing class to persist roles
     *
     * @throws IOException
     */
    protected abstract void saveRoles() throws IOException;

    @Override
    public int numberOfRoles()
    {
        return roles.size();
    }

    @Override
    public void removeUserFromAllRoles( String username ) throws ConcurrentModificationException, IOException
    {
        synchronized ( this )
        {
            Set<String> roles = rolesByUsername.get( username );
            if ( roles != null )
            {
                // Since update() is modifying the set we create a copy for the iteration
                List<String> rolesToRemoveFrom = new ArrayList<>( roles );
                for ( String roleName : rolesToRemoveFrom )
                {
                    RoleRecord role = rolesByName.get( roleName );
                    RoleRecord newRole = role.augment().withoutUser( username ).build();
                    update( role, newRole );
                }
            }
        }
    }

    @Override
    public Set<String> getAllRoleNames()
    {
        return roles.stream().map( RoleRecord::name ).collect( Collectors.toSet() );
    }

    protected void populateUserMap( RoleRecord role )
    {
        for ( String username : role.users() )
        {
            SortedSet<String> memberOfRoles = rolesByUsername.get( username );
            if ( memberOfRoles == null )
            {
                memberOfRoles = new ConcurrentSkipListSet<>();
                rolesByUsername.put( username, memberOfRoles );
            }
            memberOfRoles.add( role.name() );
        }
    }

    protected void removeFromUserMap( RoleRecord role )
    {
        for ( String username : role.users() )
        {
            SortedSet<String> memberOfRoles = rolesByUsername.get( username );
            if ( memberOfRoles != null )
            {
                memberOfRoles.remove( role.name() );
            }
        }
    }
}
