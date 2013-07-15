package com.yahoo.ycsb.db;

import static com.google.common.collect.Maps.newHashMap;
import static com.yahoo.ycsb.db.RiakUtils.*;
import static com.yahoo.ycsb.db.Constants.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicLong;

import com.basho.riak.client.IRiakObject;
import com.basho.riak.client.builders.RiakObjectBuilder;
import com.basho.riak.client.query.indexes.IntIndex;
import com.basho.riak.client.raw.RawClient;
import com.basho.riak.client.raw.RiakResponse;
import com.basho.riak.client.raw.pbc.PBClientAdapter;
import com.basho.riak.client.raw.query.indexes.IndexQuery;
import com.basho.riak.client.raw.query.indexes.IntRangeQuery;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.StringByteIterator;

public final class RiakClient extends DB {

    private static final AtomicLong SCAN_INDEX_SEQUENCE = new AtomicLong();

    private RawClient rawClient;
    private boolean use2i = false;
    private static int connectionNumber = 0;

    public static final int OK = 0;
    public static final int ERROR = -1;

    public void init() throws DBException {

        try {
            final Properties props = this.getProperties();
            use2i = Boolean.parseBoolean(props.getProperty(RIAK_USE_2I,
                    RIAK_USE_2I_DEFAULT));
            final String cluster_hosts = props.getProperty(RIAK_CLUSTER_HOSTS,
                    RIAK_CLUSTER_HOST_DEFAULT);
            final String[] servers = cluster_hosts.split(",");
            setupConnections(props, servers);
        } catch (Exception e) {

            e.printStackTrace();
            throw new DBException("Error connecting to Riak: " + e.getMessage());

        }
    }

    private void setupConnections(Properties props, String[] servers)
            throws IOException {

        final String server = servers[connectionNumber++ % servers.length];
        final String[] ipAndPort = server.split(":");
        final String ip = ipAndPort[0].trim();
        final int port = Integer.parseInt(ipAndPort[1].trim());
        final com.basho.riak.pbc.RiakClient pbcClient = new com.basho.riak.pbc.RiakClient(ip, port);
        rawClient = new PBClientAdapter(pbcClient);
    }


    @Override
    public void cleanup() throws DBException {
        rawClient.shutdown();
    }

    @Override
    public int read(final String aBucket, final String aKey,
            final Set<String> theFields, HashMap<String, ByteIterator> theResult) {

        try {

            final RiakResponse aResponse = rawClient.fetch(aBucket, aKey);
            if (aResponse.hasValue()) {
                final IRiakObject aRiakObject = aResponse.getRiakObjects()[0];
                deserializeTable(aRiakObject.getValue(), theResult);
            }

            return OK;

        } catch (Exception e) {

            e.printStackTrace();
            return ERROR;

        }
    }

    public int scan(String bucket, String startkey, int recordcount,
            Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
        if (use2i) {
            try {
                RiakResponse fetchResp = rawClient.fetch(bucket, startkey);
                Set<Long> idx = fetchResp.getRiakObjects()[0]
                        .getIntIndexV2(YCSB_INT);
                if (idx.size() == 0) {
                    System.err.println("Index not found");
                    return ERROR;
                } else {
                    Long id = idx.iterator().next();
                    long range = id + recordcount;
                    IndexQuery iq = new IntRangeQuery(IntIndex.named(YCSB_INT),
                            bucket, id, range);
                    List<String> results = rawClient.fetchIndex(iq);
                    for (String key : results) {
                        RiakResponse resp = rawClient.fetch(bucket, key);
                        HashMap<String, ByteIterator> rowResult = new HashMap<String, ByteIterator>();
                        deserializeTable(resp.getRiakObjects()[0], rowResult);
                        result.add(rowResult);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                return ERROR;
            }
            return OK;
        } else {
            return ERROR;
        }
    }

    @Override
    public int update(final String aBucket, final String aKey,
            final HashMap<String, ByteIterator> theUpdatedColumns) {

        try {

            final RiakResponse aResponse = rawClient.fetch(aBucket, aKey);
            if (aResponse.hasValue()) {

                final IRiakObject aRiakObject = aResponse.getRiakObjects()[0];

                final Map<String, ByteIterator> aTable = newHashMap();
                deserializeTable(aRiakObject.getValue(), aTable);

                final Map<String, ByteIterator> anUpdatedTable = merge(aTable,
                        theUpdatedColumns);

                // The RiakObjectBuilder#from method copies indexes -- ensuring
                // that the YCSB index is not lost on update ...
                final RiakObjectBuilder aBuilder = RiakObjectBuilder.from(
                        aRiakObject).withValue(serializeTable(anUpdatedTable));

                rawClient.store(aBuilder.build());

            }

            return OK;

        } catch (Exception e) {

            e.printStackTrace();
            return ERROR;

        }

    }

    @Override
    public int insert(String aBucket, String aKey,
            HashMap<String, ByteIterator> theColumns) {

        try {

            final RiakObjectBuilder aBuilder = RiakObjectBuilder.newBuilder(
                    aBucket, aKey).withValue(serializeTable(theColumns));

            if (use2i) {
                aBuilder.addIndex(YCSB_INT,
                        SCAN_INDEX_SEQUENCE.getAndIncrement()).build();
            }
            rawClient.store(aBuilder.build());

            return OK;

        } catch (Exception e) {

            e.printStackTrace();
            return ERROR;

        }

    }

    public int delete(String bucket, String key) {
        try {
            rawClient.delete(bucket, key);
        } catch (IOException e) {
            e.printStackTrace();
            return ERROR;
        }
        return OK;
    }

    public static void main(String[] args) {
        RiakClient cli = new RiakClient();

        Properties props = new Properties();
        props.setProperty(RIAK_CLUSTER_HOSTS,
                "localhost:10017, localhost:10027, localhost:10037");

        cli.setProperties(props);

        try {
            cli.init();
        } catch (Exception e) {
            e.printStackTrace();
        }

        String bucket = "people";
        String key = "person1";

        {
            HashMap<String, String> values = new HashMap<String, String>();
            values.put("first_name", "Dave");
            values.put("last_name", "Parfitt");
            values.put("city", "Buffalo, NY");
            cli.insert(bucket, key,
                    StringByteIterator.getByteIteratorMap(values));
            System.out.println("Added person");
        }

        {
            Set<String> fields = new HashSet<String>();
            fields.add("first_name");
            fields.add("last_name");
            HashMap<String, ByteIterator> results = new HashMap<String, ByteIterator>();
            cli.read(bucket, key, fields, results);
            System.out.println(results.toString());
            System.out.println("Read person");
        }

        {
            HashMap<String, String> updateValues = new HashMap<String, String>();
            updateValues.put("twitter", "@metadave");
            cli.update("people", "person1",
                    StringByteIterator.getByteIteratorMap(updateValues));
            System.out.println("Updated person");
        }

        {
            HashMap<String, ByteIterator> finalResults = new HashMap<String, ByteIterator>();
            cli.read(bucket, key, null, finalResults);
            System.out.println(finalResults.toString());
            System.out.println("Read person");
        }
    }
}