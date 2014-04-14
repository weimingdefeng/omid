/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.tso.util;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import com.yahoo.omid.client.TSOClient;
import com.yahoo.omid.tso.Committed;
import com.yahoo.omid.tso.RowKey;
import com.yahoo.omid.tso.TSOMessage;
import com.yahoo.omid.tso.messages.CommitResponse;
import com.yahoo.omid.tso.messages.TimestampResponse;

/**
 * Example of ChannelHandler for the Transaction Client
 * 
 * @author maysam
 * 
 */
public class ClientHandler extends TSOClient {
    
    public enum RowDistribution {
        
        UNIFORM,ZIPFIAN
    }
    
    private IntegerGenerator intGenerator;
   
   private CountDownLatch signalInitialized = new CountDownLatch(1);

   private static final Logger LOG = LoggerFactory.getLogger(ClientHandler.class);


   /**
    * Maximum number of modified rows in each transaction
    */
   final int maxTxSize;
   public static final int DEFAULT_MAX_ROW = 20;

   /**
    * The number of rows in database
    */
   private final int dbSize;
   public static final int DEFAULT_DB_SIZE = 20000000;

   public static final long PAUSE_LENGTH = 50; // in ms

   /**
    * Maximum number if outstanding messages
    */
   private final int maxInFlight;

   /**
    * Number of message to do
    */
   private final long nbMessage;

    /**
     * Number of second to run for
     */
    private volatile boolean timedOut = false;

   /**
    * Current rank (decreasing, 0 is the end of the game)
    */
   private long curMessage;

   /**
    * number of outstanding commit requests
    */
   private int outstandingTransactions = 0;

   /**
    * Start date
    */
   private Date startDate = null;

   /**
    * Stop date
    */
   private Date stopDate = null;

   /**
    * Return value for the caller
    */
   final BlockingQueue<Boolean> answer = new LinkedBlockingQueue<Boolean>();

   private Committed committed = new Committed();
   private Set<Long> aborted = Collections.synchronizedSet(new HashSet<Long>(100000));

   /*
    * For statistial purposes
    */
   public ConcurrentHashMap<Long, Long> wallClockTime = new ConcurrentHashMap<Long, Long>(); 

   public long totalNanoTime = 0;
   public long totalTx = 0;
   
   private Channel channel;

   private float percentReads;



   /**
    * Method to wait for the final response
    * 
    * @return success or not
    */
   public boolean waitForAll() {
      for (;;) {
         try {
            return answer.take();
         } catch (InterruptedException e) {
            // Ignore.
         }
      }
   }

   /**
    * Constructor
    * 
    * @param nbMessage
    * @param inflight
    * @throws IOException
    */
   public ClientHandler(Configuration conf, int runFor, long nbMessage, int inflight, boolean pauseClient, 
           float percentReads, int maxTxSize, final int dbSize, IntegerGenerator intGenerator) throws IOException {
       super(conf);
       if (nbMessage < 0) {
           throw new IllegalArgumentException("nbMessage: " + nbMessage);
       }
       this.maxInFlight = inflight;
       this.nbMessage = nbMessage;
       this.curMessage = nbMessage;
       this.pauseClient = pauseClient;
       this.percentReads = percentReads;
       this.maxTxSize = maxTxSize;
       this.dbSize = dbSize;
       this.intGenerator = intGenerator;
       this.signalInitialized.countDown();

       executor.schedule(new Runnable() {
               @Override
               public void run() {
                   timedOut = true;
               }
           }, runFor, TimeUnit.SECONDS);
   }

   /**
    * Starts the traffic
    */
    //   @Override FIXME
   public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
       //super.channelConnected(ctx, e);
      try {
          // don't serve before ClientHandler fully initialized
          signalInitialized.await();
      } catch (InterruptedException e1) {
          throw new RuntimeException(e1);
      }
      startDate = new Date();
      channel = e.getChannel();
      outstandingTransactions = 0;
      startTransaction();
   }

   
    //@Override
   synchronized public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
       // TSO client will try to reconnect. Ignore that behaviour for this test
   } 

/**
    * If write of Commit Request was not possible before, just do it now
    */
    //@Override
   public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e) {
      startTransaction();
   }

   /**
    * When the channel is closed, print result
    */
    //@Override
   public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
       //super.channelClosed(ctx, e);
      stopDate = new Date();
      String MB = String.format("Memory Used: %8.3f MB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime()
            .freeMemory()) / 1048576.0);
      String Mbs = String.format("%9.3f TPS",
            ((nbMessage - curMessage) * 1000 / (float) (stopDate.getTime() - (startDate != null ? startDate.getTime()
                  : 0))));
      LOG.info(MB + " " + Mbs);
   }

   /**
    * When a message is received, handle it based on its type
    * @throws IOException 
    */
   //@Override
   protected void processMessage(TSOMessage msg) {
      if (msg instanceof CommitResponse) {
         handle((CommitResponse) msg);
      } else if (msg instanceof TimestampResponse) {
         handle((TimestampResponse) msg);
      }
   }

   /**
    * Handle the TimestampResponse message
    */
   public void handle(TimestampResponse timestampResponse) {
      sendCommitRequest(timestampResponse.timestamp);
   }

   /**
    * Handle the CommitRequest message
    */
   private long lasttotalTx = 0;
   private long lasttotalNanoTime = 0;
   private long lastTimeout = System.currentTimeMillis();

   public void handle(CommitResponse msg) {
      // outstandingTransactions.decrementAndGet();
      outstandingTransactions--;
      long finishNanoTime = System.nanoTime();
      long startNanoTime = wallClockTime.remove(msg.startTimestamp);
      if (msg.committed) {
         totalNanoTime += (finishNanoTime - startNanoTime);
         totalTx++;
         long timeout = System.currentTimeMillis();
         // if (totalTx % 10000 == 0) {//print out
         if (timeout - lastTimeout > 60 * 1000) { // print out
            long difftx = totalTx - lasttotalTx;
            long difftime = totalNanoTime - lasttotalNanoTime;
            System.out.format(
                  " CLIENT: totalTx: %d totalNanoTime: %d microtime/tx: %4.3f tx/s %4.3f "
                        + "Size Com: %d Size Aborted: %d Memory Used: %8.3f KB TPS:  %9.3f \n",
                  difftx,
                  difftime,
                  (difftime / (double) difftx / 1000),
                  1000 * difftx / ((double) (timeout - lastTimeout)),
                  getSizeCom(),
                  getSizeAborted(),
//                  largestDeletedTimestamp - _decoder.lastStartTimestamp,
                  (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0,
                  ((nbMessage - curMessage) * 1000 / (float) (new Date().getTime() - (startDate != null ? startDate
                        .getTime() : 0))));
            lasttotalTx = totalTx;
            lasttotalNanoTime = totalNanoTime;
            lastTimeout = timeout;
         }
      } else {// aborted
         // try {
         //    super.completeAbort(msg.startTimestamp, new SyncAbortCompleteCallback());
         // } catch (IOException e) {
         //    LOG.error("Couldn't send abort", e);
         // }
      }
      startTransaction();
   }

   private long getSizeCom() {
      return committed.getSize();
   }

   private long getSizeAborted() {
      return aborted.size() * 8 * 8;
   }

   private java.util.Random rnd;

   private boolean pauseClient;

   /**
    * Sends the CommitRequest message to the channel
    * 
    * @param timestamp
    * @param channel
    */
   private void sendCommitRequest(final long timestamp) {
      if (!((channel.getInterestOps() & Channel.OP_WRITE) == 0))
         return;

      // initialize rnd if it is not yet
      if (rnd == null) {
         long seed = System.currentTimeMillis();
         seed *= channel.getId();// to make it channel dependent
         rnd = new java.util.Random(seed);
      }

      boolean readOnly = (rnd.nextFloat() * 100) < percentReads;

      int size = readOnly ? 0 : rnd.nextInt(maxTxSize);
      final RowKey [] rows = new RowKey[size];
      for (byte i = 0; i < rows.length; i++) {
         long l = intGenerator.nextInt();
         byte[] b = new byte[8];
         for (int iii = 0; iii < 8; iii++) {
            b[7 - iii] = (byte) (l >>> (iii * 8));
         }
         byte[] tableId = new byte[8];
         rows[i] = new RowKey(b, tableId);
      }

      // send a query once in a while
      totalCommitRequestSent++;
      if (totalCommitRequestSent % QUERY_RATE == 0 && rows.length > 0) {
         long queryTimeStamp = rnd.nextInt(Math.abs((int) timestamp));
         try {
             //isCommitted(timestamp, queryTimeStamp, new SyncCommitQueryCallback());
             throw new IOException();
         } catch (IOException e) {
            LOG.error("Couldn't send commit query", e);
         }
      }

      executor.schedule(new Runnable() {
         @Override
         public void run() {
            // keep statistics
            wallClockTime.put(timestamp, System.nanoTime());

            // try {
            //    commit(timestamp, rows, new SyncCommitCallback());
            // } catch (IOException e) {
            //    LOG.error("Couldn't send commit", e);
            //    e.printStackTrace();
            // }
         }
      }, pauseClient ? PAUSE_LENGTH : 0, TimeUnit.MILLISECONDS);

   }

	private static ScheduledExecutorService executor = Executors.newScheduledThreadPool(20, new ThreadFactoryBuilder()
			.setNameFormat("Commit handler %d").build());

   private long totalCommitRequestSent;// just to keep the total number of
                                       // commitreqeusts sent
   private int QUERY_RATE = 100;// send a query after this number of commit
                                // requests

   /**
    * Start a new transaction
    * 
    * @param channel
    * @throws IOException 
    */
   private void startTransaction() {
      while (true) {// fill the pipe with as much as request you can
         if (!((channel.getInterestOps() & Channel.OP_WRITE) == 0))
            return;

         // if (outstandingTransactions.intValue() >= MAX_IN_FLIGHT)
         if (outstandingTransactions >= maxInFlight)
            return;

         if (curMessage == 0 || timedOut) {
            // wait for all outstanding msgs and then close the channel
            // if (outstandingTransactions.intValue() == 0) {
            if (outstandingTransactions == 0) {
                LOG.info("No more messages and all outstanding messages consumed. Stopping benchmark, closing channel ["
                        + channel.getId() + "]");
                channel.close().addListener(new ChannelFutureListener() {
                  public void operationComplete(ChannelFuture future) {
                     answer.offer(true);
                  }
               });
            }
            return;
         }
         curMessage--;
         outstandingTransactions++;
         // try {
         //    super.getNewTimestamp(new SyncCreateCallback());
         // } catch (IOException e) {
         //    LOG.error("Couldn't start transaction", e);
         // }

         Thread.yield();
      }
   }
}
