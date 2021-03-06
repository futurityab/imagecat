package org.apache.solr.handler.component;

/*
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

import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.io.IOException;

import org.apache.solr.BaseDistributedSearchTestCase;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.PivotField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;

/**
 * test demonstrating how overrequesting helps finds top-terms in the "long tail" 
 * of shards that don't have even distributions of terms (something that can be common
 * in cases of custom sharding -- even if you don't know that there is a corrolation 
 * between the property you are sharding on and the property you are faceting on).
 *
 * NOTE: This test ignores the control collection (in single node mode, there is no 
 * need for the overrequesting, all the data is local -- so comparisons with it wouldn't 
 * be valid in the cases we are testing here)
 */
public class DistributedFacetPivotLongTailTest extends BaseDistributedSearchTestCase {
  
  public DistributedFacetPivotLongTailTest(){
    this.fixShardCount = true;
    this.shardCount = 3;
  }

  private int docNumber = 0;
  
  public int getDocNum() {
    docNumber++;
    return docNumber;
  }

  @Override
  public void doTest() throws Exception {

    final SolrServer shard0 = clients.get(0);
    final SolrServer shard1 = clients.get(1);
    final SolrServer shard2 = clients.get(2);
    
    // the 5 top foo_s terms have 100 docs each on every shard
    for (int i = 0; i < 100; i++) {
      for (int j = 0; j < 5; j++) {
        shard0.add(sdoc("id", getDocNum(), "foo_s", "aaa"+j));
        shard1.add(sdoc("id", getDocNum(), "foo_s", "aaa"+j));
        shard2.add(sdoc("id", getDocNum(), "foo_s", "aaa"+j));
      }
    }

    // 20 foo_s terms that come in "second" with 50 docs each 
    // on both shard0 & shard1 ("bbb_")
    for (int i = 0; i < 50; i++) {
      for (int j = 0; j < 20; j++) {
        shard0.add(sdoc("id", getDocNum(), "foo_s", "bbb"+j));
        shard1.add(sdoc("id", getDocNum(), "foo_s", "bbb"+j));
      }
      // distracting term appears on only on shard2 50 times
      shard2.add(sdoc("id", getDocNum(), "foo_s", "junkA"));
    }
    // put "bbb0" on shard2 exactly once to sanity check refinement
    shard2.add(sdoc("id", getDocNum(), "foo_s", "bbb0"));

    // long 'tail' foo_s term appears in 45 docs on every shard
    // foo_s:tail is the only term with bar_s sub-pivot terms
    for (int i = 0; i < 45; i++) {

      // for sub-pivot, shard0 & shard1 have 6 docs each for "tailB"
      // but the top 5 terms are ccc(0-4) -- 7 on each shard
      // (4 docs each have junk terms)
      String sub_term = (i < 35) ? "ccc"+(i % 5) : ((i < 41) ? "tailB" : "junkA");
      shard0.add(sdoc("id", getDocNum(), "foo_s", "tail", "bar_s", sub_term));
      shard1.add(sdoc("id", getDocNum(), "foo_s", "tail", "bar_s", sub_term));

      // shard2's top 5 sub-pivot terms are junk only it has with 8 docs each
      // and 5 docs that use "tailB"
      sub_term = (i < 40) ? "junkB"+(i % 5) : "tailB";
      shard2.add(sdoc("id", getDocNum(), "foo_s", "tail", "bar_s", sub_term));
    }

    // really long tail uncommon foo_s terms on shard2
    for (int i = 0; i < 30; i++) {
      shard2.add(sdoc("id", getDocNum(), "foo_s", "zzz"+i));
    }

    commit();

    SolrParams req = params( "q", "*:*", 
                             "distrib", "false",
                             "facet", "true", 
                             "facet.limit", "10",
                             "facet.pivot", "foo_s,bar_s");

    // sanity check that our expectations about each shard (non-distrib) are correct

    PivotField pivot = null;
    List<PivotField> pivots = null;
    List<PivotField>[] shardPivots = new List[3];
    shardPivots[0] = shard0.query( req ).getFacetPivot().get("foo_s,bar_s");
    shardPivots[1] = shard1.query( req ).getFacetPivot().get("foo_s,bar_s");
    shardPivots[2] = shard2.query( req ).getFacetPivot().get("foo_s,bar_s");

    // top 5 same on all shards
    for (int i = 0; i < 3; i++) {
      assertEquals(10, shardPivots[i].size());
      for (int j = 0; j < 5; j++) {
        pivot = shardPivots[i].get(j);
        assertEquals(pivot.toString(), "aaa"+j, pivot.getValue());
        assertEquals(pivot.toString(), 100, pivot.getCount());
      }
    }
    // top 6-10 same on shard0 & shard11
    for (int i = 0; i < 2; i++) {
      for (int j = 5; j < 10; j++) {
        pivot = shardPivots[i].get(j);
        assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("bbb"));
        assertEquals(pivot.toString(), 50, pivot.getCount());
      }
    }
    // 6-10 on shard2
    assertEquals("junkA", shardPivots[2].get(5).getValue());
    assertEquals(50, shardPivots[2].get(5).getCount());
    assertEquals("tail", shardPivots[2].get(6).getValue());
    assertEquals(45, shardPivots[2].get(6).getCount());
    assertEquals("bbb0", shardPivots[2].get(7).getValue());
    assertEquals(1, shardPivots[2].get(7).getCount());
    for (int j = 8; j < 10; j++) {
      pivot = shardPivots[2].get(j);
      assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("zzz"));
      assertEquals(pivot.toString(), 1, pivot.getCount());
    }
    // check sub-shardPivots on "tail" from shard2
    pivots = shardPivots[2].get(6).getPivot();
    assertEquals(6, pivots.size());
    for (int j = 0; j < 5; j++) {
      pivot = pivots.get(j);
      assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("junkB"));
      assertEquals(pivot.toString(), 8, pivot.getCount());
    }
    pivot = pivots.get(5);
    assertEquals("tailB", pivot.getValue());
    assertEquals(5, pivot.getCount());

    // if we disable overrequesting, we don't find the long tail

    pivots = queryServer( params( "q", "*:*",
                                  "shards", getShardsString(),
                                  FacetParams.FACET_OVERREQUEST_COUNT, "0",
                                  FacetParams.FACET_OVERREQUEST_RATIO, "0",
                                  "facet", "true",
                                  "facet.limit", "6",
                                  "facet.pivot", "foo_s,bar_s" ) 
                          ).getFacetPivot().get("foo_s,bar_s");
    assertEquals(6, pivots.size());
    for (int i = 0; i < 5; i++) {
      pivot = pivots.get(i);
      assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("aaa"));
      assertEquals(pivot.toString(), 300, pivot.getCount());
    }
    // even w/o the long tail, we should have still asked shard2 to refine bbb0
    assertTrue(pivots.get(5).toString(), pivots.get(5).getValue().equals("bbb0"));
    assertEquals(pivots.get(5).toString(), 101, pivots.get(5).getCount());

    // with default overrequesting, we should find the correct top 6 including 
    // long tail and top sub-pivots
    // (even if we disable overrequesting on the sub-pivot)
    for (ModifiableSolrParams q : new ModifiableSolrParams[] { 
        params(),
        params("f.bar_s.facet.overrequest.ratio","0",
               "f.bar_s.facet.overrequest.count","0")      }) {
      
      q.add( params( "q", "*:*",
                     "shards", getShardsString(),
                     "facet", "true",
                     "facet.limit", "6",
                     "facet.pivot", "foo_s,bar_s" ));
      pivots = queryServer( q ).getFacetPivot().get("foo_s,bar_s");
        
      assertEquals(6, pivots.size());
      for (int i = 0; i < 5; i++) {
        pivot = pivots.get(i);
        assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("aaa"));
        assertEquals(pivot.toString(), 300, pivot.getCount());
      }
      pivot = pivots.get(5);
      assertEquals(pivot.toString(), "tail", pivot.getValue());
      assertEquals(pivot.toString(), 135, pivot.getCount());
      // check the sub pivots
      pivots = pivot.getPivot();
      assertEquals(6, pivots.size());
      pivot = pivots.get(0);
      assertEquals(pivot.toString(), "tailB", pivot.getValue());
      assertEquals(pivot.toString(), 17, pivot.getCount());
      for (int i = 1; i < 6; i++) { // ccc(0-4)
        pivot = pivots.get(i);
        assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("ccc"));
        assertEquals(pivot.toString(), 14, pivot.getCount());
      }
    }

    // if we lower the facet.limit on the sub-pivot, overrequesting should still ensure 
    // that we get the correct top5 including "tailB"

    pivots = queryServer( params( "q", "*:*",
                                  "shards", getShardsString(),
                                  "facet", "true",
                                  "facet.limit", "6",
                                  "f.bar_s.facet.limit", "5",
                                  "facet.pivot", "foo_s,bar_s" )
                          ).getFacetPivot().get("foo_s,bar_s");
    assertEquals(6, pivots.size());
    for (int i = 0; i < 5; i++) {
      pivot = pivots.get(i);
      assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("aaa"));
      assertEquals(pivot.toString(), 300, pivot.getCount());
    }
    pivot = pivots.get(5);
    assertEquals(pivot.toString(), "tail", pivot.getValue());
    assertEquals(pivot.toString(), 135, pivot.getCount());
    // check the sub pivots
    pivots = pivot.getPivot();
    assertEquals(5, pivots.size());
    pivot = pivots.get(0);
    assertEquals(pivot.toString(), "tailB", pivot.getValue());
    assertEquals(pivot.toString(), 17, pivot.getCount());
    for (int i = 1; i < 5; i++) { // ccc(0-3)
      pivot = pivots.get(i);
      assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("ccc"));
      assertEquals(pivot.toString(), 14, pivot.getCount());
    }

    // however with a lower limit and overrequesting disabled, 
    // we're going to miss out on tailB

    pivots = queryServer( params( "q", "*:*",
                                  "shards", getShardsString(),
                                  "facet", "true",
                                  "facet.limit", "6",
                                  "f.bar_s.facet.overrequest.ratio", "0",
                                  "f.bar_s.facet.overrequest.count", "0",
                                  "f.bar_s.facet.limit", "5",
                                  "facet.pivot", "foo_s,bar_s" )
                          ).getFacetPivot().get("foo_s,bar_s");
    assertEquals(6, pivots.size());
    for (int i = 0; i < 5; i++) {
      pivot = pivots.get(i);
      assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("aaa"));
      assertEquals(pivot.toString(), 300, pivot.getCount());
    }
    pivot = pivots.get(5);
    assertEquals(pivot.toString(), "tail", pivot.getValue());
    assertEquals(pivot.toString(), 135, pivot.getCount());
    // check the sub pivots
    pivots = pivot.getPivot();
    assertEquals(5, pivots.size());
    for (int i = 0; i < 5; i++) { // ccc(0-4)
      pivot = pivots.get(i);
      assertTrue(pivot.toString(), pivot.getValue().toString().startsWith("ccc"));
      assertEquals(pivot.toString(), 14, pivot.getCount());
    }
  }

}
