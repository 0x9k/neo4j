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

import java.io.IOException;
import java.time.Clock;
import java.util.Map;

import org.neo4j.graphdb.security.AuthorizationViolationException;
import org.neo4j.kernel.api.security.AuthManager;
import org.neo4j.kernel.api.security.AuthSubject;
import org.neo4j.kernel.api.security.AuthToken;
import org.neo4j.kernel.api.security.AuthenticationResult;
import org.neo4j.kernel.api.security.exception.IllegalCredentialsException;
import org.neo4j.kernel.api.security.exception.InvalidAuthTokenException;
import org.neo4j.server.security.auth.exception.ConcurrentModificationException;

/**
 * Manages server authentication and authorization.
 * <p>
 * Through the BasicAuthManager you can create, update and delete users, and authenticate using credentials.
 * <p>>
 * NOTE: AuthManager will manage the lifecycle of the given UserRepository,
 *       so the given UserRepository should not be added to another LifeSupport.
 * </p>
 */
public class BasicAuthManager implements AuthManager, UserManager
{
    protected final AuthenticationStrategy authStrategy;
    protected final UserRepository users;
    protected final PasswordPolicy passwordPolicy;
    protected final boolean authEnabled;

    public BasicAuthManager( UserRepository users, PasswordPolicy passwordPolicy, AuthenticationStrategy authStrategy, boolean authEnabled )
    {
        this.users = users;
        this.passwordPolicy = passwordPolicy;
        this.authStrategy = authStrategy;
        this.authEnabled = authEnabled;
    }

    public BasicAuthManager( UserRepository users, PasswordPolicy passwordPolicy, AuthenticationStrategy authStrategy )
    {
        this( users, passwordPolicy, authStrategy, true );
    }

    public BasicAuthManager( UserRepository users, PasswordPolicy passwordPolicy, Clock clock, boolean authEnabled )
    {
        this( users, passwordPolicy, new RateLimitedAuthenticationStrategy( clock, 3 ), authEnabled );
    }

    @Override
    public void init() throws Throwable
    {
        users.init();
    }

    @Override
    public void start() throws Throwable
    {
        users.start();

        if ( authEnabled && users.numberOfUsers() == 0 )
        {
            newUser( "neo4j", "neo4j", true );
        }
    }

    @Override
    public void stop() throws Throwable
    {
        users.stop();
    }

    @Override
    public void shutdown() throws Throwable
    {
        users.shutdown();
    }

    @Override
    public AuthSubject login( Map<String,Object> authToken ) throws InvalidAuthTokenException
    {
        assertAuthEnabled();

        String username = AuthToken.safeCast( AuthToken.PRINCIPAL, authToken );
        String password = AuthToken.safeCast( AuthToken.CREDENTIALS, authToken );

        User user = users.getUserByName( username );
        AuthenticationResult result = AuthenticationResult.FAILURE;
        if ( user != null )
        {
            result = authStrategy.authenticate( user, password );
            if ( result == AuthenticationResult.SUCCESS && user.passwordChangeRequired() )
            {
                result = AuthenticationResult.PASSWORD_CHANGE_REQUIRED;
            }
        }
        return new BasicAuthSubject( this, user, result );
    }

    @Override
    public User newUser( String username, String initialPassword, boolean requirePasswordChange ) throws IOException,
            IllegalCredentialsException
    {
        assertAuthEnabled();
        assertValidName( username );
        User user = new User.Builder()
                .withName( username )
                .withCredentials( Credential.forPassword( initialPassword ) )
                .withRequiredPasswordChange( requirePasswordChange )
                .build();
        users.create( user );
        return user;
    }

    @Override
    public boolean deleteUser( String username ) throws IOException
    {
        assertAuthEnabled();
        User user = users.getUserByName( username );
        return user != null && users.delete( user );
    }

    @Override
    public User getUser( String username )
    {
        assertAuthEnabled();
        return users.getUserByName( username );
    }

    public void setPassword( AuthSubject authSubject, String username, String password ) throws IOException,
            IllegalCredentialsException
    {
        BasicAuthSubject basicAuthSubject = BasicAuthSubject.castOrFail( authSubject );

        if ( !basicAuthSubject.doesUsernameMatch( username ) )
        {
            throw new AuthorizationViolationException( "Invalid attempt to change the password for user " + username );
        }

        setUserPassword( username, password );
    }

    @Override
    public void setUserPassword( String username, String password ) throws IOException,
            IllegalCredentialsException
    {
        assertAuthEnabled();
        User existingUser = users.getUserByName( username );
        if ( existingUser == null )
        {
            throw new IllegalCredentialsException( "User " + username + " does not exist" );
        }

        passwordPolicy.validatePassword( password );

        if ( existingUser.credentials().matchesPassword( password ) )
        {
            throw new IllegalCredentialsException( "Old password and new password cannot be the same." );
        }

        try
        {
            User updatedUser = existingUser.augment()
                    .withCredentials( Credential.forPassword( password ) )
                    .withRequiredPasswordChange( false )
                    .build();
            users.update( existingUser, updatedUser );
        } catch ( ConcurrentModificationException e )
        {
            // try again
            setUserPassword( username, password );
        }
    }

    protected void assertAuthEnabled()
    {
        if ( !authEnabled )
        {
            throw new IllegalStateException( "Auth not enabled" );
        }
    }

    private void assertValidName( String name )
    {
        if ( !users.isValidUsername( name ) )
        {
            throw new IllegalArgumentException( "User name contains illegal characters. Please use simple ascii characters and numbers." );
        }
    }
}
