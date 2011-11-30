package org.apache.solr.cloud;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.junit.BeforeClass;

/**
 *
 */
public class RecoveryZkTest extends FullDistributedZkTest {
  
  @BeforeClass
  public static void beforeSuperClass() throws Exception {

  }
  
  public RecoveryZkTest() {
    super();
    sliceCount = 1;
    shardCount = 2;
  }
  
  @Override
  public void doTest() throws Exception {
    
    handle.clear();
    handle.put("QTime", SKIPVAL);
    handle.put("timestamp", SKIPVAL);
    
    del("*:*");
    
    //printLayout();
    
    // start an indexing thread
    
    class StopableThread extends Thread {
      private volatile boolean stop = false;
      
      {
        setDaemon(true);
      }
      
      @Override
      public void run() {
        int i = 0;
        while (true && !stop) {
          try {
            indexr(id, i++, i1, 50, tlong, 50, t1,
                "to come to the aid of their country.");
          } catch (ThreadDeath td) {
            throw td;
          } catch (Exception e) {
            //e.printStackTrace();
          }
        }
      }
      
      public void safeStop() {
        stop = true;
      }
      
    };
    
    StopableThread indexThread = new StopableThread();
    indexThread.start();
    
    StopableThread indexThread2 = new StopableThread();
    
    indexThread2.start();

    // give some time to index...
    Thread.sleep(4000);
    
    // bring shard replica down
    JettySolrRunner replica = chaosMonkey.killShard("shard1", 1);
    
    // wait a moment
    Thread.sleep(1000);

    
    // bring shard replica up
    replica.start();
    
    // wait for recovery to complete
    String shard1State = "";
    
    do  {
      Thread.sleep(1000);
      updateMappingsFromZk(jettys, clients);
      ZkNodeProps props = jettyToInfo.get(replica);
      shard1State = props.get(ZkStateReader.STATE_PROP);
    } while(!shard1State.equals(ZkStateReader.ACTIVE));
    
    // stop indexing threads
    indexThread.safeStop();
    indexThread2.safeStop();
    
    commit();
    
    // test that leader and replica have same doc count
    
    long client1Docs = shardToClient.get("shard1").get(0).query(new SolrQuery("*:*")).getResults().getNumFound();
    long client2Docs = shardToClient.get("shard1").get(1).query(new SolrQuery("*:*")).getResults().getNumFound();
    
    assertTrue(client1Docs > 0);
    assertEquals(client1Docs, client2Docs);
    
    // TODO: right now the control and distrib are usually off by a few docs...
    //query("q", "*:*", "distrib", true, "sort", i1 + " desc");
  }
  
  protected void indexDoc(SolrInputDocument doc) throws IOException, SolrServerException {
    controlClient.add(doc);

    // nocommit: look into why cloudClient.addDoc returns NPE
    UpdateRequest ureq = new UpdateRequest();
    ureq.add(doc);
    ureq.setParam("update.chain", "distrib-update-chain");
    ureq.process(cloudClient);
  }
  
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
  }
  
}
