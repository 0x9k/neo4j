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
package org.neo4j.kernel.api.impl.index.partition;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.io.IOUtils;
import org.neo4j.kernel.api.impl.index.IndexWriterFactories;
import org.neo4j.kernel.api.impl.index.backup.LuceneIndexSnapshotFileIterator;

public class IndexPartition implements Closeable
{
    private final IndexWriter indexWriter;
    private final Directory directory;
    private final SearcherManager searcherManager;
    private final File indexDirectory;

    public IndexPartition( File indexDirectory, Directory directory ) throws IOException
    {
        this.indexDirectory = indexDirectory;
        this.directory = directory;
        this.indexWriter = new IndexWriter( directory, IndexWriterFactories.standardConfig() );
        this.searcherManager = new SearcherManager( indexWriter, true, new SearcherFactory() );
    }

    public IndexWriter getIndexWriter()
    {
        return indexWriter;
    }

    public PartitionSearcher acquireSearcher() throws IOException
    {
        return new PartitionSearcher( searcherManager );
    }

    public void maybeRefresh() throws IOException
    {
        searcherManager.maybeRefresh();
    }

    public void maybeRefreshBlocking() throws IOException
    {
        searcherManager.maybeRefreshBlocking();
    }

    @Override
    public void close() throws IOException
    {
        IOUtils.closeAll( searcherManager, indexWriter, directory );
    }

    public ResourceIterator<File> snapshot() throws IOException
    {
        return LuceneIndexSnapshotFileIterator.forIndex( indexDirectory, indexWriter );
    }
}
