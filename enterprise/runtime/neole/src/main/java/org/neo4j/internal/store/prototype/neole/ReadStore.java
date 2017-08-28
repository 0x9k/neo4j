/*
 * Copyright (c) 2002-2017 "Neo Technology,"
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
package org.neo4j.internal.store.prototype.neole;

import java.io.File;
import java.io.IOException;

import org.neo4j.internal.kernel.api.IndexPredicate;
import org.neo4j.internal.kernel.api.IndexReference;
import org.neo4j.internal.kernel.api.Read;
import org.neo4j.internal.kernel.api.Scan;
import org.neo4j.internal.store.cursors.MemoryManager;
import org.neo4j.internal.store.cursors.ReadCursor;

import static org.neo4j.internal.store.prototype.neole.EdgeCursor.NO_EDGE;
import static org.neo4j.internal.store.prototype.neole.PartialPropertyCursor.NO_PROPERTIES;
import static org.neo4j.internal.store.prototype.neole.StoreFile.fixedSizeRecordFile;

public class ReadStore extends MemoryManager implements Read
{
    private static final String NODE_STORE = "neostore.nodestore.db", EDGE_STORE = "neostore.relationshipstore.db",
            EDGE_GROUP_STORE = "neostore.relationshipgroupstore.db", PROPERTY_STORE = "neostore.propertystore.db";
    private static final long INTEGER_MINUS_ONE = 0xFFFF_FFFFL;
    private final StoreFile nodes, edges, edgeGroups, properties;

    public ReadStore( File storeDir ) throws IOException
    {
        this.nodes = fixedSizeRecordFile( new File( storeDir, NODE_STORE ), NodeCursor.RECORD_SIZE );
        this.edges = fixedSizeRecordFile( new File( storeDir, EDGE_STORE ), EdgeCursor.RECORD_SIZE );
        this.edgeGroups = fixedSizeRecordFile( new File( storeDir, EDGE_GROUP_STORE ), EdgeGroupCursor.RECORD_SIZE );
        this.properties = fixedSizeRecordFile( new File( storeDir, PROPERTY_STORE ), PropertyCursor.RECORD_SIZE );
    }

    @Override
    public void nodeIndexSeek(
            IndexReference index,
            org.neo4j.internal.kernel.api.NodeValueIndexCursor cursor,
            IndexPredicate... predicates )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void nodeIndexScan( IndexReference index, org.neo4j.internal.kernel.api.NodeValueIndexCursor cursor )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void nodeLabelScan( int label, org.neo4j.internal.kernel.api.NodeLabelIndexCursor cursor )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public Scan<org.neo4j.internal.kernel.api.NodeLabelIndexCursor> nodeLabelScan( int label )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void allNodesScan( org.neo4j.internal.kernel.api.NodeCursor cursor )
    {
        ((NodeCursor) cursor).init( nodes, 0, nodes.maxReference );
    }

    @Override
    public Scan<org.neo4j.internal.kernel.api.NodeCursor> allNodesScan()
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void singleNode( long reference, org.neo4j.internal.kernel.api.NodeCursor cursor )
    {
        ((NodeCursor) cursor).init( nodes, reference, reference );
    }

    @Override
    public void singleEdge( long reference, org.neo4j.internal.kernel.api.EdgeScanCursor cursor )
    {
        ((EdgeScanCursor) cursor).init( edges, reference, reference );
    }

    @Override
    public void allEdgesScan( org.neo4j.internal.kernel.api.EdgeScanCursor cursor )
    {
        ((EdgeScanCursor) cursor).init( edges, 0, edges.maxReference );
    }

    @Override
    public Scan<org.neo4j.internal.kernel.api.EdgeScanCursor> allEdgesScan()
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void edgeLabelScan( int label, org.neo4j.internal.kernel.api.EdgeScanCursor cursor )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public Scan<org.neo4j.internal.kernel.api.EdgeScanCursor> edgeLabelScan( int label )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void edgeGroups( long nodeReference, long reference, org.neo4j.internal.kernel.api.EdgeGroupCursor cursor )
    {
        ((EdgeGroupCursor) cursor).init( edgeGroups, edges, nodeReference, reference );
    }

    @Override
    public void edges( long nodeReference, long reference, org.neo4j.internal.kernel.api.EdgeTraversalCursor cursor )
    {
        if ( reference == NO_EDGE )
        {
            cursor.close();
        }
        else
        {
            ((EdgeTraversalCursor) cursor).init( edges, nodeReference, reference );
        }
    }

    @Override
    public void nodeProperties( long reference, org.neo4j.internal.kernel.api.PropertyCursor cursor )
    {
        if ( reference == NO_PROPERTIES )
        {
            cursor.close();
        }
        else
        {
            ((PropertyCursor) cursor).init( properties, reference );
        }
    }

    @Override
    public void edgeProperties( long reference, org.neo4j.internal.kernel.api.PropertyCursor cursor )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void futureNodeReferenceRead( long reference )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void futureEdgeReferenceRead( long reference )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void futureNodePropertyReferenceRead( long reference )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    @Override
    public void futureEdgePropertyReferenceRead( long reference )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    public void block( long reference, ByteBlockCursor cursor )
    {
        throw new UnsupportedOperationException( "not implemented" );
    }

    public void shutdown()
    {
        IllegalStateException failure = null;
        for ( StoreFile file : new StoreFile[] {nodes} )
        {
            try
            {
                file.close();
            }
            catch ( Exception e )
            {
                if ( failure == null )
                {
                    failure = new IllegalStateException( "Failed to close store files." );
                }
                failure.addSuppressed( e );
            }
        }
        if ( failure != null )
        {
            throw failure;
        }
    }

    static int nextPowerOfTwo( int v )
    {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v++;
        return v;
    }

    static long combineReference( long base, long modifier )
    {
        return modifier == 0 && base == INTEGER_MINUS_ONE ? -1 : base | modifier;
    }

    static int lcm( int a, int b )
    {
        return (a / gcd( a, b )) * b;
    }

    private static int gcd( int a, int b )
    {
        return a == b ? a : a > b ? gcd( a - b, b ) : gcd( a, b - a );
    }

    public CursorFactory cursorFactory()
    {
        return new CursorFactory( this );
    }

    int dynamicStoreRecordSize()
    {
        throw new UnsupportedOperationException( "not implemented" );
    }
}
