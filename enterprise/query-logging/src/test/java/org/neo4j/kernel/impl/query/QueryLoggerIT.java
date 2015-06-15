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
package org.neo4j.kernel.impl.query;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Settings;
import org.neo4j.test.DatabaseRule;
import org.neo4j.test.EphemeralFileSystemRule;
import org.neo4j.test.ImpermanentDatabaseRule;
import org.neo4j.test.TestGraphDatabaseFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class QueryLoggerIT
{
    @Rule
    public TestRule ruleOrder()
    {
        return RuleChain.outerRule( fs ).around( db );
    }

    private final File logFile = new File( "target/test-data/impermanent-db/queries.log" ).getAbsoluteFile();
    private final EphemeralFileSystemRule fs = new EphemeralFileSystemRule();
    private final DatabaseRule db = new ImpermanentDatabaseRule()
    {
        @Override
        protected GraphDatabaseFactory newFactory()
        {
            return ((TestGraphDatabaseFactory) super.newFactory()).setFileSystem( fs.get() );
        }

        @Override
        protected GraphDatabaseBuilder newBuilder( GraphDatabaseFactory factory )
        {
            return super.newBuilder( factory )
                    .setConfig( GraphDatabaseSettings.log_queries, Settings.TRUE )
                    .setConfig( GraphDatabaseSettings.log_queries_filename, logFile.getAbsolutePath() );
        }
    };

    @Test
    public void shouldLogQuerySlowerThanThreshold() throws Exception
    {
        String QUERY = "CREATE (n:Foo{bar:\"baz\"})";
        db.getGraphDatabaseService().execute( QUERY );

        db.shutdown();

        List<String> logLines = new ArrayList<>();
        try ( BufferedReader reader = new BufferedReader( fs.get().openAsReader( logFile, "UTF-8" ) ) )
        {
            for ( String line; (line = reader.readLine()) != null; )
            {
                logLines.add( line );
            }
        }

        assertEquals( 1, logLines.size() );
        assertThat( logLines.get( 0 ), Matchers.endsWith( String.format( " ms: %s - %s",
                QueryEngineProvider.embeddedSession(), QUERY ) ) );
    }
}
