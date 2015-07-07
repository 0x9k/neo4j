/*
 * Copyright (c) 2002-2015 "Neo Technology,"
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
package org.neo4j.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;

import org.neo4j.io.fs.FileUtils;
import org.neo4j.test.SuppressOutput;
import org.neo4j.test.server.ExclusiveServerTestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public abstract class BaseBootstrapperTest extends ExclusiveServerTestBase
{
    @Rule
    public final SuppressOutput suppressOutput = SuppressOutput.suppressAll();

    private Bootstrapper bootstrapper;

    @Before
    @After
    public void cleanUpAfterBootstrapper() throws Exception
    {
        if ( bootstrapper != null )
        {
            String baseDir = bootstrapper.getServer().getDatabase().getLocation();
            bootstrapper.stop();
            FileUtils.deleteRecursively( new File( baseDir ) );
        }
    }

    protected abstract Bootstrapper newBootstrapper();

    @Test
    public void shouldStartStopNeoServerWithoutAnyConfigFiles()
    {
        // Given
        bootstrapper = newBootstrapper();

        // When
        Integer resultCode = bootstrapper.start();

        // Then
        assertEquals( Bootstrapper.OK, resultCode );
        assertNotNull( bootstrapper.getServer() );

        bootstrapper.stop();
    }
}
