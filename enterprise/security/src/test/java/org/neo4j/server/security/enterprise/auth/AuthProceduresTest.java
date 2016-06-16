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

import org.apache.shiro.authc.AuthenticationException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.security.AuthorizationViolationException;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.api.security.AuthSubject;
import org.neo4j.kernel.api.security.AuthenticationResult;
import org.neo4j.kernel.impl.proc.Procedures;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.server.security.auth.BasicPasswordPolicy;
import org.neo4j.server.security.auth.InMemoryUserRepository;
import org.neo4j.test.TestEnterpriseGraphDatabaseFactory;

import static java.time.Clock.systemUTC;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.server.security.auth.SecurityTestUtils.authToken;

public class AuthProceduresTest
{
    private AuthSubject adminSubject;
    private AuthSubject schemaSubject;
    private AuthSubject writeSubject;
    private AuthSubject readSubject;
    private AuthSubject noneSubject;

    private GraphDatabaseAPI db;
    private ShiroAuthManager manager;

    @Before
    public void setUp() throws Throwable
    {
        db = (GraphDatabaseAPI) new TestEnterpriseGraphDatabaseFactory().newImpermanentDatabase();
        manager = new EnterpriseAuthManager( new InMemoryUserRepository(), new InMemoryRoleRepository(),
                new BasicPasswordPolicy(), systemUTC(), true );
        manager.init();
        manager.start();
        manager.newUser( "noneSubject", "abc", false );
        manager.newUser( "adminSubject", "abc", false );
        manager.newUser( "schemaSubject", "abc", false );
        manager.newUser( "readWriteSubject", "abc", false );
        manager.newUser( "readSubject", "123", false );
        // Currently admin role is created by default
        manager.addUserToRole( "adminSubject", PredefinedRolesBuilder.ADMIN );
        manager.newRole( PredefinedRolesBuilder.ARCHITECT, "schemaSubject" );
        manager.newRole( PredefinedRolesBuilder.PUBLISHER, "readWriteSubject" );
        manager.newRole( PredefinedRolesBuilder.READER, "readSubject" );
        noneSubject = manager.login( authToken( "noneSubject", "abc" ) );
        readSubject = manager.login( authToken( "readSubject", "123" ) );
        writeSubject = manager.login( authToken( "readWriteSubject", "abc" ) );
        schemaSubject = manager.login( authToken( "schemaSubject", "abc" ) );
        adminSubject = manager.login( authToken( "adminSubject", "abc" ) );
        db.execute( "UNWIND range(0,2) AS number CREATE (:Node {number:number})" );
    }

    @After
    public void tearDown() throws Throwable
    {
        db.shutdown();
        manager.stop();
        manager.shutdown();
    }

    @Test
    public void shouldAllowUserChangePassword() throws Exception
    {
        testCallEmpty( db, readSubject, "CALL dbms.changePassword( '321' )", null );
        AuthSubject subject = manager.login( authToken( "readSubject", "321" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
    }

    //----------User creation -----------

    @Test
    public void shouldCreateUser() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('craig', '1234', true)");
        assertNotNull( "User craig should exist", manager.getUser( "craig" ) );
    }

    @Test
    public void shouldNotCreateExistingUser() throws Exception
    {

        testCallEmpty( db, adminSubject, "CALL dbms.createUser('craig', '1234', true)");
        assertNotNull( "User craig should exist", manager.getUser( "craig" ) );
        try
        {
            testCallEmpty( db, adminSubject, "CALL dbms.createUser('craig', '1234', true)");
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain 'The specified user already exists''",
                    e.getMessage().contains( "The specified user already exists" ) );
        }
    }

    @Test
    public void shouldNotAllowNonAdminCreateUser() throws Exception
    {
        testFailCreateUser( noneSubject );
        testFailCreateUser( readSubject );
        testFailCreateUser( writeSubject );
        testFailCreateUser( schemaSubject );
    }

    //----------User creation scenarios-----------

    /*
    Admin creates user Henrik with password bar
    Admin adds user Henrik to role Reader
    Henrik logs in with incorrect password → fail
    Henrik logs in with correct password → gets prompted to change
    Henrik starts read transaction → permission denied
    Henrik changes password to foo → ok
    Henrik starts write transaction → permission denied
    Henrik starts read transaction → ok
    Henrik logs off
    */
    @Test
    public void userCreation1() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', true)");
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.READER + "')",
                null );
        AuthSubject subject = manager.login( authToken( "Henrik", "foo" ) );
        assertEquals( AuthenticationResult.FAILURE, subject.getAuthenticationResult() );
        subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.PASSWORD_CHANGE_REQUIRED, subject.getAuthenticationResult() );
        testFailReadAction( subject, 3L );
        testCallEmpty( db, subject, "CALL dbms.changePassword( 'foo' )");
        subject = manager.login( authToken( "Henrik", "foo" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailWriteAction( subject );
        testSuccessfulReadAction( subject, 3L );
    }

    /*
    Admin creates user Henrik with password bar
    Henrik logs in with correct password (gets prompted to change - change to foo)
    Henrik starts read transaction → permission denied
    Admin adds user Henrik to role Reader
    Henrik starts write transaction → permission denied
    Henrik starts read transaction → ok
    Henrik logs off
    */
    @Test
    public void userCreation2() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', true)" );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.PASSWORD_CHANGE_REQUIRED, subject.getAuthenticationResult() );
        testCallEmpty( db, subject, "CALL dbms.changePassword( 'foo' )" );
        subject = manager.login( authToken( "Henrik", "foo" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailReadAction( subject, 3L );
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.READER + "')",
                null );
        testFailWriteAction( subject );
        testSuccessfulReadAction( subject, 3L );
    }

    /*
    Admin creates user Henrik with password bar
    Henrik logs in with correct password
    Henrik starts read transaction → permission denied
    Admin adds user Henrik to role Publisher
    Henrik starts write transaction → ok
    Henrik starts read transaction → ok
    Henrik starts schema transaction → permission denied
    Henrik logs off
    */
    @Test
    public void userCreation3() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)" );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailReadAction( subject, 3L );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testSuccessfulWriteAction( subject );
        testSuccessfulReadAction( subject, 4L );
        testFailSchema( subject );
    }

    /*
    Admin creates user Henrik with password bar
    Henrik logs in with correct password
    Henrik starts read transaction → permission denied
    Henrik starts write transaction → permission denied
    Henrik starts schema transaction → permission denied
    Henrik creates user Craig → permission denied
    Admin adds user Henrik to role Architect
    Henrik starts write transaction → ok
    Henrik starts read transaction → ok
    Henrik starts schema transaction → ok
    Henrik creates user Craig → permission denied
    Henrik logs off
    */
    @Test
    public void userCreation4() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)");
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailReadAction( subject, 3L );
        testFailWriteAction( subject );
        testFailSchema( subject );
        testFailCreateUser( subject );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.ARCHITECT + "')");
        testSuccessfulWriteAction( subject );
        testSuccessfulReadAction( subject, 4L );
        testSuccessfulSchemaAction( subject );
        testFailCreateUser( subject );
    }

    //----------User and role management-----------
    @Test
    public void shouldAllowAddingAndRemovingUserFromRole() throws Exception
    {
        assertFalse( "Should not have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        assertTrue( "Should have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        testCallEmpty( db, adminSubject,
                "CALL dbms.removeUserFromRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        assertFalse( "Should not have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
    }

    @Test
    public void shouldAllowAddingUserToRoleMultipleTimes() throws Exception
    {
        assertFalse( "Should not have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        assertTrue( "Should have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
    }

    @Test
    public void shouldAllowRemovingUserFromRoleMultipleTimes() throws Exception
    {
        assertFalse( "Should not have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        assertTrue( "Should have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        testCallEmpty( db, adminSubject,
                "CALL dbms.removeUserFromRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testCallEmpty( db, adminSubject,
                "CALL dbms.removeUserFromRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        assertFalse( "Should not have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
    }

    @Test
    public void shouldAllowAddingAndRemovingUserFromMultipleRoles() throws Exception
    {
        assertFalse( "Should not have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        assertFalse( "Should not have role architect",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.ARCHITECT ) );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('readSubject', '" + PredefinedRolesBuilder.ARCHITECT + "')");
        assertTrue( "Should have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        assertTrue( "Should have role architect",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.ARCHITECT ) );

        testCallEmpty( db, adminSubject,
                "CALL dbms.removeUserFromRole('readSubject', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testCallEmpty( db, adminSubject,
                "CALL dbms.removeUserFromRole('readSubject', '" + PredefinedRolesBuilder.ARCHITECT + "')");
        assertFalse( "Should not have role publisher",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.PUBLISHER ) );
        assertFalse( "Should not have role architect",
                ShiroAuthSubject.castOrFail( readSubject ).getSubject().hasRole( PredefinedRolesBuilder.ARCHITECT ) );
    }

    @Test
    public void shouldNotAllowNonAdminAddingUserToRole() throws Exception
    {
        testFailAddUserToRoleAction( noneSubject );
        testFailAddUserToRoleAction( readSubject );
        testFailAddUserToRoleAction( writeSubject );
        testFailAddUserToRoleAction( schemaSubject );
    }

    @Test
    public void shouldNotAllowNonAdminRemovingUserFromRole() throws Exception
    {
        testFailRemoveUserFromRoleAction( noneSubject );
        testFailRemoveUserFromRoleAction( readSubject );
        testFailRemoveUserFromRoleAction( writeSubject );
        testFailRemoveUserFromRoleAction( schemaSubject );
    }

    /*
    Admin creates user Henrik with password bar
    Admin adds user Henrik to role Publisher
    Henrik logs in with correct password
    Henrik starts transaction with write query → ok
    Admin removes user Henrik from role Publisher
    Henrik starts transaction with read query → permission denied
    Admin adds Henrik to role Reader
    Henrik starts transaction with write query → permission denied
    Henrik starts transaction with read query → ok
    */
    @Test
    public void roleManagement1() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)");
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testSuccessfulWriteAction( subject );
        testCallEmpty( db, adminSubject,
                "CALL dbms.removeUserFromRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testFailReadAction( subject, 4L );
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.READER + "')",
                null );
        testFailWriteAction( subject );
        testSuccessfulReadAction( subject, 4L );
    }

    /*
    Admin creates user Henrik with password bar
    Henrik logs in with correct password
    Henrik starts transaction with write query → permission denied
    Admin adds user Henrik to role Publisher → ok
    Admin adds user Henrik to role Publisher → ok
    Henrik starts transaction with write query → ok
    */
    @Test
    public void roleManagement2() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)");
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailWriteAction( subject );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testSuccessfulWriteAction( subject );
    }

    /*
    Admin creates user Henrik with password bar
    Admin adds user Henrik to role Publisher
    Henrik logs in with correct password
    Admin adds user Henrik to role Reader
    Henrik starts transaction with write query → ok
    Henrik starts transaction with read query → ok
    Admin removes user Henrik from role Publisher
    Henrik starts transaction with write query → permission denied
    Henrik starts transaction with read query → ok
    */
    @Test
    public void roleManagement3() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)");
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')" );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.READER + "')",
                null );
        testSuccessfulWriteAction( subject );
        testSuccessfulReadAction( subject, 4L );
        testCallEmpty( db, adminSubject,
                "CALL dbms.removeUserFromRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')");
        testFailWriteAction( subject );
        testSuccessfulReadAction( subject, 4L );
    }

    //----------User deletion -----------

    @Test
    public void shouldDeleteUser() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Craig', '1234', true)", null );
        assertNotNull( "User Craig should exist", manager.getUser( "Craig" ) );
        testCallEmpty( db, adminSubject, "CALL dbms.deleteUser('Craig')", null );
        assertNull( "User Craig should not exist", manager.getUser( "Craig" ) );
    }

    @Test
    public void shouldNotAllowNonAdminDeleteUser() throws Exception
    {
        testFailDeleteUser( noneSubject );
        testFailDeleteUser( readSubject );
        testFailDeleteUser( writeSubject );
        testFailDeleteUser( schemaSubject );
    }

    @Test
    public void shouldAllowDeletingUserMultipleTimes() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Craig', '1234', true)", null );
        assertNotNull( "User Craig should exist", manager.getUser( "Craig" ) );
        testCallEmpty( db, adminSubject, "CALL dbms.deleteUser('Craig')", null );
        assertNull( "User Craig should not exist", manager.getUser( "Craig" ) );
        try
        {
            testCallEmpty( db, adminSubject, "CALL dbms.deleteUser('Craig')", null );
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain 'The user 'Craig' does not exist''",
                    e.getMessage().contains( "The user 'Craig' does not exist" ) );
        }
    }

    /*
    Admin creates user Henrik with password bar
    Admin deletes user Henrik
    Henrik logs in with correct password → fail
    */
    @Test
    public void userDeletion1() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        testCallEmpty( db, adminSubject, "CALL dbms.deleteUser('Henrik')", null );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.FAILURE, subject.getAuthenticationResult() );
    }

    /*
    Admin creates user Henrik with password bar
    Admin deletes user Henrik
    Admin adds user Henrik to role Publisher → fail
    */
    @Test
    public void userDeletion2() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        testCallEmpty( db, adminSubject, "CALL dbms.deleteUser('Henrik')", null );
        try
        {
            testCallEmpty( db, adminSubject,
                    "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')", null );
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain 'User Henrik does not exist'",
                    e.getMessage().contains( "User Henrik does not exist" ) );
        }
    }

    /*
    Admin creates user Henrik with password bar
    Admin adds user Henrik to role Publisher
    Admin deletes user Henrik
    Admin removes user Henrik from role Publisher → fail
    */
    @Test
    public void userDeletion3() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')", null );
        testCallEmpty( db, adminSubject, "CALL dbms.deleteUser('Henrik')", null );
        try
        {
            testCallEmpty( db, adminSubject,
                    "CALL dbms.removeUserFromRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')", null );
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain 'User Henrik does not exist'",
                    e.getMessage().contains( "User Henrik does not exist" ) );
        }
    }

    /*
    Admin creates user Henrik with password bar
    Admin adds user Henrik to role Publisher
    User Henrik logs in with correct password → ok
    Admin deletes user Henrik
    Henrik starts transaction with read query → fail
    */
    @Test
    public void userDeletion4() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        testCallEmpty( db, adminSubject,
                "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.PUBLISHER + "')", null );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testCallEmpty( db, adminSubject, "CALL dbms.deleteUser('Henrik')", null );
        try
        {
            testSuccessfulReadAction( subject, 3L );
            fail( "Expected exception to be thrown" );
        }
        catch ( AuthenticationException e )
        {
            assertTrue( "Exception should contain 'User Henrik does not exist'",
                    e.getMessage().contains( "User Henrik does not exist" ) );
        }
    }

    //----------User suspension scenarios-----------

    @Test
    public void shouldSuspendUser() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.suspendUser('readSubject')" );
        assertTrue( manager.getUser( "readSubject" ).hasFlag( FileUserRealm.IS_SUSPENDED ) );
    }

    @Test
    public void shouldActivateUser() throws Exception
    {
        manager.suspendUser( "readSubject" );
        testCallEmpty( db, adminSubject, "CALL dbms.activateUser('readSubject')" );
        assertFalse( manager.getUser( "readSubject" ).hasFlag( FileUserRealm.IS_SUSPENDED ) );
    }

    @Test
    public void shouldFailOnNonAdminSuspend() throws Exception
    {
        testCallFail( db, schemaSubject, "CALL dbms.suspendUser('readSubject')",
                QueryExecutionException.class, AuthProcedures.PERMISSION_DENIED );
    }

    @Test
    public void shouldFailOnNonAdminActivate() throws Exception
    {
        manager.suspendUser( "readSubject" );
        testCallFail( db, schemaSubject, "CALL dbms.activateUser('readSubject')",
                QueryExecutionException.class, AuthProcedures.PERMISSION_DENIED );
    }

    /*
    Admin creates user Henrik with password bar
    Henrik logs in with correct password → ok
    Henrik logs off
    Admin suspends user Henrik
    User Henrik logs in with correct password → fail
     */
    @Test
    public void userSuspension1() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)" );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        subject.logout();
        testCallEmpty( db, adminSubject, "CALL dbms.suspendUser('Henrik')" );
        subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.FAILURE, subject.getAuthenticationResult() );
    }

    /*
    Admin creates user Henrik with password bar
    Admin adds user Henrik to role Reader
    Henrik logs in with correct password → ok
    Henrik starts and completes transaction with read query → ok
    Admin suspends user Henrik
    Henrik’s session is terminated
    Henrik logs in with correct password → fail
     */
    @Test
    public void userSuspension2() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)" );
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.READER + "')" );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testSuccessfulReadAction( subject, 3L );
        testCallEmpty( db, adminSubject, "CALL dbms.suspendUser('Henrik')" );
        testFailReadAction( subject, 3L );
        // TODO: Check that user session is terminated instead of checking failed read
        subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.FAILURE, subject.getAuthenticationResult() );
    }

    //----------User suspension and activation / reinstating scenarios-----------

    /*
    Admin creates user Henrik with password bar
    Admin suspends user Henrik
    Henrik logs in with correct password → fail
    Admin reinstates user Henrik
    Henrik logs in with correct password → ok
     */
    @Test
    public void userActivation1() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)" );
        testCallEmpty( db, adminSubject, "CALL dbms.suspendUser('Henrik')" );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.FAILURE, subject.getAuthenticationResult() );
        testCallEmpty( db, adminSubject, "CALL dbms.activateUser('Henrik')" );
        subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
    }
    //----------List users/roles-----------

    @Test
    public void shouldReturnUsers() throws Exception
    {
        testResult( db, adminSubject, "CALL dbms.listUsers() YIELD value AS users RETURN users", r -> {
            List<Object> names = getObjectsAsList( r, "users" );
            Assert.assertThat( names,
                    containsInAnyOrder( "adminSubject", "readSubject", "schemaSubject", "readWriteSubject",
                            "noneSubject", "neo4j" ) );
            assertEquals( 6, names.size() );
        } );
    }

    @Test
    public void shouldNotAllowNonAdminListUsers() throws Exception
    {
        testFailListUsers( noneSubject, 5L );
        testFailListUsers( readSubject, 5L );
        testFailListUsers( writeSubject, 5L );
        testFailListUsers( schemaSubject, 5L );
    }

    /*
    Admin lists all users → ok
    Admin creates user Henrik with password bar
    Admin lists all users → ok
    Henrik logs in with correct password → ok
    Henrik lists all users → permission denied
    Admin adds user Henrik to role Admin
    Henrik lists all users → ok
    */
    @Test
    public void userListing() throws Exception
    {
        testResult( db, adminSubject, "CALL dbms.listUsers() YIELD value AS users RETURN users", r -> {
            List<Object> names = getObjectsAsList( r, "users" );
            Assert.assertThat( names,
                    containsInAnyOrder( "adminSubject", "readSubject", "schemaSubject", "readWriteSubject",
                            "noneSubject", "neo4j" ) );
            assertEquals( 6, names.size() );
        } );
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        testResult( db, adminSubject, "CALL dbms.listUsers() YIELD value AS users RETURN users", r -> {
            List<Object> names = getObjectsAsList( r, "users" );
            Assert.assertThat( names,
                    containsInAnyOrder( "Henrik", "adminSubject", "readSubject", "schemaSubject", "readWriteSubject",
                            "noneSubject", "neo4j" ) );
            assertEquals( 7, names.size() );
        } );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailListUsers( subject, 6L );
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.ADMIN + "')",
                null );
        testResult( db, subject, "CALL dbms.listUsers() YIELD value AS users RETURN users", r -> {
            List<Object> names = getObjectsAsList( r, "users" );
            Assert.assertThat( names,
                    containsInAnyOrder( "Henrik", "adminSubject", "readSubject", "schemaSubject", "readWriteSubject",
                            "noneSubject", "neo4j" ) );
            assertEquals( 7, names.size() );
        } );
    }

    @Test
    public void shouldReturnRoles() throws Exception
    {
        testResult( db, adminSubject, "CALL dbms.listRoles() YIELD value AS roles RETURN roles", r -> {
            List<Object> roles = getObjectsAsList( r, "roles" );
            Assert.assertThat( roles,
                    containsInAnyOrder( PredefinedRolesBuilder.ADMIN, PredefinedRolesBuilder.ARCHITECT,
                            PredefinedRolesBuilder.PUBLISHER, PredefinedRolesBuilder.READER ) );
            assertEquals( 4, roles.size() );
        } );
    }

    @Test
    public void shouldNotAllowNonAdminListRoles() throws Exception
    {
        testFailListRoles( noneSubject );
        testFailListRoles( readSubject );
        testFailListRoles( writeSubject );
        testFailListRoles( schemaSubject );
    }

    /*
    Admin creates user Henrik with password bar
    Henrik logs in with correct password → ok
    Henrik lists all roles → permission denied
    Admin lists all roles → ok
    Admin adds user Henrik to role Admin
    Henrik lists all roles → ok
    */
    @Test
    public void rolesListing() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailListRoles( subject );
        testSuccessfulListRolesAction( adminSubject );
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Henrik', '" + PredefinedRolesBuilder.ADMIN + "')",
                null );
        testSuccessfulListRolesAction( subject );
    }

    @Test
    public void shouldListRolesForUser() throws Exception
    {
        testResult( db, adminSubject, "CALL dbms.listRolesForUser('adminSubject') YIELD value as roles RETURN roles",
                r -> {
                    List<Object> roles = getObjectsAsList( r, "roles" );
                    Assert.assertThat( roles, containsInAnyOrder( PredefinedRolesBuilder.ADMIN ) );
                    assertEquals( 1, roles.size() );
                } );
    }

    @Test
    public void shouldNotAllowNonAdminListUserRoles() throws Exception
    {
        testFailListUserRoles( noneSubject, "adminSubject" );
        testFailListUserRoles( readSubject, "adminSubject" );
        testFailListUserRoles( writeSubject, "adminSubject" );
        testFailListUserRoles( schemaSubject, "adminSubject" );
    }

    @Test
    public void shouldFailToListRolesForUnknownUser() throws Exception
    {
        try
        {
            testResult( db, adminSubject, "CALL dbms.listRolesForUser('Henrik') YIELD value as roles RETURN roles",
                    r -> assertFalse( r.hasNext() ) );
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain 'User Henrik does not exist.' but was " + e.getMessage(),
                    e.getMessage().contains( "User Henrik does not exist." ) );
        }
    }

    @Test
    public void shouldListNoRolesForUserWithNoRoles() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        testResult( db, adminSubject, "CALL dbms.listRolesForUser('Henrik') YIELD value as roles RETURN roles",
                r -> assertFalse( r.hasNext() ) );
    }

    /*
    Admin creates user Henrik with password bar
    Admin creates user Craig with password foo
    Admin adds user Craig to role Publisher
    Henrik logs in with correct password → ok
    Henrik lists all roles for user Craig → permission denied
    Admin lists all roles for user Craig → ok
    */
    @Test
    public void listingUserRoles() throws Exception
    {
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Henrik', 'bar', false)", null );
        testCallEmpty( db, adminSubject, "CALL dbms.createUser('Craig', 'foo', false)", null );
        testCallEmpty( db, adminSubject, "CALL dbms.addUserToRole('Craig', '" + PredefinedRolesBuilder.PUBLISHER + "')",
                null );
        AuthSubject subject = manager.login( authToken( "Henrik", "bar" ) );
        assertEquals( AuthenticationResult.SUCCESS, subject.getAuthenticationResult() );
        testFailListUserRoles( subject, "Craig" );
        testResult( db, adminSubject, "CALL dbms.listRolesForUser('Craig') YIELD value as roles RETURN roles",
                r -> {
                    List<Object> roles = getObjectsAsList( r, "roles" );
                    Assert.assertThat( roles, containsInAnyOrder( PredefinedRolesBuilder.PUBLISHER ) );
                    assertEquals( 1, roles.size() );
                } );
    }

    //-------------Helper functions---------------

    private void testSuccessfulReadAction( AuthSubject subject, Long count )
    {
        testCall( db, subject, "MATCH (n) RETURN count(n)", ( r ) -> assertEquals( r.get( "count(n)" ), count ) );
    }

    private void testFailReadAction( AuthSubject subject, Long count )
    {
        try
        {
            testSuccessfulReadAction( subject, count );
            fail( "Expected exception to be thrown" );
        }
        catch ( AuthorizationViolationException e )
        {
            // TODO: this should be permission denied instead
            assertTrue( "Exception should contain 'Read operations are not allowed'",
                    e.getMessage().contains( "Read operations are not allowed" ) );
        }
    }

    private void testSuccessfulWriteAction( AuthSubject subject )
    {
        testCallEmpty( db, subject, "CREATE (:Node)");
    }

    private void testFailWriteAction( AuthSubject subject )
    {
        try
        {
            testSuccessfulWriteAction( subject );
            fail( "Expected exception to be thrown" );
        }
        catch ( AuthorizationViolationException e )
        {
            // TODO: this should be permission denied instead
            assertTrue( "Exception should contain 'Write operations are not allowed'",
                    e.getMessage().contains( "Write operations are not allowed" ) );
        }
    }

    private void testSuccessfulSchemaAction( AuthSubject subject )
    {
        testCallEmpty( db, subject, "CREATE INDEX ON :Node(number)");
    }

    private void testFailSchema( AuthSubject subject )
    {
        try
        {
            testSuccessfulSchemaAction( subject );
            fail( "Expected exception to be thrown" );
        }
        catch ( AuthorizationViolationException e )
        {
            // TODO: this should be permission denied instead
            assertTrue( "Exception should contain 'Schema operations are not allowed'",
                    e.getMessage().contains( "Schema operations are not allowed" ) );
        }
    }

    private void testFailCreateUser( AuthSubject subject )
    {
        try
        {
            testCallEmpty( db, subject, "CALL dbms.createUser('Craig', 'foo', false)");
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain '" + AuthProcedures.PERMISSION_DENIED + "'",
                    e.getMessage().contains( AuthProcedures.PERMISSION_DENIED ) );
        }
    }

    private void testFailAddUserToRoleAction( AuthSubject subject )
    {
        try
        {
            testCallEmpty( db, subject, "CALL dbms.addUserToRole('Craig', '" + PredefinedRolesBuilder.PUBLISHER + "')",
                    null );
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain '" + AuthProcedures.PERMISSION_DENIED + "'",
                    e.getMessage().contains( AuthProcedures.PERMISSION_DENIED ) );
        }
    }

    private void testFailRemoveUserFromRoleAction( AuthSubject subject )
    {
        try
        {
            testCallEmpty( db, subject,
                    "CALL dbms.removeUserFromRole('Craig', '" + PredefinedRolesBuilder.PUBLISHER + "')");
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain '" + AuthProcedures.PERMISSION_DENIED + "'",
                    e.getMessage().contains( AuthProcedures.PERMISSION_DENIED ) );
        }
    }

    private void testFailDeleteUser( AuthSubject subject )
    {
        try
        {
            testCallEmpty( db, subject, "CALL dbms.deleteUser('Craig')", null );
            fail( "Expected exception to be thrown" );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain '" + AuthProcedures.PERMISSION_DENIED + "'",
                    e.getMessage().contains( AuthProcedures.PERMISSION_DENIED ) );
        }
    }

    private void testSuccessfulListUsersAction( AuthSubject subject, Long count )
    {
        testCall( db, subject, "CALL dbms.listUsers() YIELD value AS users RETURN count(users)",
                ( r ) -> assertEquals( r.get( "count(users)" ), count ) );
    }

    private void testFailListUsers( AuthSubject subject, Long count )
    {
        try
        {
            testSuccessfulListUsersAction( subject, count );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain '" + AuthProcedures.PERMISSION_DENIED + "'",
                    e.getMessage().contains( AuthProcedures.PERMISSION_DENIED ) );
        }
    }

    private void testSuccessfulListRolesAction( AuthSubject subject )
    {
        testCall( db, subject, "CALL dbms.listRoles() YIELD value AS roles RETURN count(roles)",
                ( r ) -> assertEquals( r.get( "count(roles)" ), 4L ) );
    }

    private void testFailListRoles( AuthSubject subject )
    {
        try
        {
            testSuccessfulListRolesAction( subject );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain '" + AuthProcedures.PERMISSION_DENIED + "'",
                    e.getMessage().contains( AuthProcedures.PERMISSION_DENIED ) );
        }
    }

    private void testSuccessfulListUserRolesAction( AuthSubject subject, String username )
    {
        testCall( db, subject,
                "CALL dbms.listRolesForUser('" + username + "') YIELD value AS roles RETURN count(roles)", Map::clear );
    }

    private void testFailListUserRoles( AuthSubject subject, String username )
    {
        try
        {
            testSuccessfulListUserRolesAction( subject, username );
        }
        catch ( QueryExecutionException e )
        {
            assertTrue( "Exception should contain '" + AuthProcedures.PERMISSION_DENIED + "'",
                    e.getMessage().contains( AuthProcedures.PERMISSION_DENIED ) );
        }
    }

    private List<Object> getObjectsAsList( Result r, String key )
    {
        return r.stream().map( s -> s.get( key ) ).collect( Collectors.toList() );
    }

    private static void testCall( GraphDatabaseAPI db, AuthSubject subject, String call,
            Consumer<Map<String,Object>> consumer )
    {
        testCall( db, subject, call, null, consumer );
    }

    private static void testCall( GraphDatabaseAPI db, AuthSubject subject, String call, Map<String,Object> params,
            Consumer<Map<String,Object>> consumer )
    {
        testResult( db, subject, call, params, ( res ) -> {
            if ( res.hasNext() )
            {
                Map<String,Object> row = res.next();
                consumer.accept( row );
            }
            assertFalse( res.hasNext() );
        } );
    }

    public static void testCallFail( GraphDatabaseAPI db, AuthSubject subject, String call, Class expectedExceptionClass,
            String partOfErrorMsg )
    {
        try
        {
            testCallEmpty( db, subject, call, null);
            fail( "Expected exception to be thrown" );
        }
        catch ( Exception e )
        {
            assertEquals( expectedExceptionClass, e.getClass() );
            assertTrue( "Exception should contain '" + partOfErrorMsg + "'",
                    e.getMessage().contains( partOfErrorMsg ) );
        }
    }

    public static void testCallEmpty( GraphDatabaseAPI db, AuthSubject subject, String call )
    {
        testCallEmpty( db, subject, call, null);
    }

    public static void testCallEmpty( GraphDatabaseAPI db, AuthSubject subject, String call, Map<String,Object> params )
    {
        testResult( db, subject, call, params, ( res ) -> assertFalse( "Expected no results", res.hasNext() ) );
    }

    public static void testCallCount( GraphDatabaseAPI db, AuthSubject subject, String call, Map<String,Object> params,
            final int count )
    {
        testResult( db, subject, call, params, ( res ) -> {
            int left = count;
            while ( left > 0 )
            {
                assertTrue( "Expected " + count + " results, but got only " + (count - left), res.hasNext() );
                res.next();
                left--;
            }
            assertFalse( "Expected " + count + " results, but there are more ", res.hasNext() );
        } );
    }

    public static void testResult( GraphDatabaseAPI db, AuthSubject subject, String call,
            Consumer<Result> resultConsumer )
    {
        testResult( db, subject, call, null, resultConsumer );
    }

    public static void testResult( GraphDatabaseAPI db, AuthSubject subject, String call, Map<String,Object> params,
            Consumer<Result> resultConsumer )
    {
        try ( Transaction tx = db.beginTransaction( KernelTransaction.Type.explicit, subject ) )
        {
            Map<String,Object> p = (params == null) ? Collections.<String,Object>emptyMap() : params;
            resultConsumer.accept( db.execute( call, p ) );
            tx.success();
        }
    }

    public static void registerProcedures( GraphDatabaseAPI db ) throws KernelException
    {
        Procedures procedures = db.getDependencyResolver().resolveDependency( Procedures.class );
        (new ProceduresProvider()).registerProcedures( procedures );
    }
}
