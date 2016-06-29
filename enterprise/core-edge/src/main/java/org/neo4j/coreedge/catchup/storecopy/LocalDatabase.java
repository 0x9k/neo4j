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
package org.neo4j.coreedge.catchup.storecopy;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

import org.neo4j.coreedge.catchup.storecopy.edge.CopiedStoreRecovery;
import org.neo4j.coreedge.catchup.storecopy.edge.StoreFetcher;
import org.neo4j.coreedge.server.CoreMember;
import org.neo4j.coreedge.server.StoreId;
import org.neo4j.kernel.NeoStoreDataSource;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.internal.DatabaseHealth;

public class LocalDatabase
{
    private final File storeDir;

    private final CopiedStoreRecovery copiedStoreRecovery;
    private final StoreFiles storeFiles;
    private Supplier<NeoStoreDataSource> neoDataSourceSupplier;
    private final Supplier<TransactionIdStore> transactionIdStoreSupplier;
    private final Supplier<DatabaseHealth> databaseHealthSupplier;

    private volatile StoreId storeId;
    private volatile DatabaseHealth databaseHealth;

    public LocalDatabase(
            File storeDir,
            CopiedStoreRecovery copiedStoreRecovery, StoreFiles storeFiles,
            Supplier<NeoStoreDataSource> neoDataSourceSupplier,
            Supplier<TransactionIdStore> transactionIdStoreSupplier,
            Supplier<DatabaseHealth> databaseHealthSupplier )
    {
        this.storeDir = storeDir;
        this.copiedStoreRecovery = copiedStoreRecovery;
        this.storeFiles = storeFiles;
        this.neoDataSourceSupplier = neoDataSourceSupplier;
        this.transactionIdStoreSupplier = transactionIdStoreSupplier;
        this.databaseHealthSupplier = databaseHealthSupplier;
    }

    public void start() throws IOException
    {
        neoDataSourceSupplier.get().start();
    }

    public void stop()
    {
        clearCache();
        neoDataSourceSupplier.get().stop();
    }

    public StoreId storeId()
    {
        if ( storeId == null )
        {
            org.neo4j.kernel.impl.store.StoreId kernelStoreId = neoDataSourceSupplier.get().getStoreId();
            storeId = new StoreId( kernelStoreId.getCreationTime(),
                    kernelStoreId.getRandomId(), kernelStoreId.getUpgradeTime(), kernelStoreId.getUpgradeId() );
        }
        return storeId;
    }

    public void deleteStore() throws IOException
    {
        storeFiles.delete( storeDir );
    }

    public void panic( Throwable cause )
    {
        getDatabaseHealth().panic( cause );
    }

    public <EXCEPTION extends Throwable> void assertHealthy( Class<EXCEPTION> cause ) throws EXCEPTION
    {
        getDatabaseHealth().assertHealthy( cause );
    }

    private DatabaseHealth getDatabaseHealth()
    {
        if ( databaseHealth == null )
        {
            databaseHealth = databaseHealthSupplier.get();
        }
        return databaseHealth;
    }

    public void copyStoreFrom( CoreMember from, StoreFetcher storeFetcher ) throws StoreCopyFailedException
    {
        try
        {
            storeFiles.delete( storeDir );

            TemporaryStoreDirectory tempStore = new TemporaryStoreDirectory( storeDir );
            storeFetcher.copyStore( from, tempStore.storeDir() );
            copiedStoreRecovery.recoverCopiedStore( tempStore.storeDir() );
            storeFiles.moveTo( tempStore.storeDir(), storeDir );
        }
        catch ( IOException e )
        {
            throw new StoreCopyFailedException( e );
        }
    }

    public boolean isEmpty()
    {
        return transactionIdStoreSupplier.get().getLastCommittedTransactionId() == TransactionIdStore.BASE_TX_ID;
    }

    private void clearCache()
    {
        storeId = null;
        databaseHealth = null;
    }
}
