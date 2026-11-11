import local.sidecar.copilot.BoundedPcmQueue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.reflect.Field;

/** Synthetic JVM checks under existing pause, storage-full and control-boundary scenarios. */
public final class BoundedPcmQueueTest {
    private static final AtomicInteger checks=new AtomicInteger();
    private static void check(boolean value,String reason){
        if(!value)throw new AssertionError(reason);checks.incrementAndGet();
    }
    private static void waitFor(CountDownLatch latch)throws Exception{
        if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("latch timeout");
    }
    private static void join(Thread thread)throws Exception{
        thread.join(5000);if(thread.isAlive())throw new AssertionError("thread timeout: "+thread.getName());
    }
    private static Exception await(BoundedPcmQueue queue)throws Exception{
        AtomicReference<Exception> error=new AtomicReference<>();
        Thread waiter=new Thread(()->{try{queue.awaitFinished();}catch(Exception e){error.set(e);}},"bounded-await");
        waiter.setDaemon(true);waiter.start();join(waiter);
        check(queue.isFinished(),"completion published");return error.get();
    }
    private static short[] pcm(int count,short value){short[] p=new short[count];Arrays.fill(p,value);return p;}
    private static boolean zero(short[] values){for(short value:values)if(value!=0)return false;return true;}
    private static class Recorder implements BoundedPcmQueue.Sink {
        final List<short[]> retained=new ArrayList<>(),contents=new ArrayList<>();
        final List<Long> times=new ArrayList<>();
        long accepted,observed;boolean overflow;Exception failure;int finishes;
        public void accept(short[] data,int count,long received)throws Exception{
            retained.add(data);contents.add(data.clone());times.add(received);
            check(count==data.length,"owned item count");
        }
        public void finish(long accepted,long observed,boolean overflow,Exception failure)throws Exception{
            this.accepted=accepted;this.observed=observed;this.overflow=overflow;this.failure=failure;finishes++;
        }
    }

    private static void stalledWriterAndOverflow()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),signal=new CountDownLatch(1);
        Recorder sink=new Recorder(){
            public void accept(short[] p,int n,long t)throws Exception{
                if(contents.isEmpty()){entered.countDown();waitFor(release);}super.accept(p,n,t);
            }
            public void finish(long a,long o,boolean f,Exception e)throws Exception{
                check(signal.getCount()==0,"overflow dispatched before finalization");super.finish(a,o,f,e);
            }
        };
        AtomicInteger faults=new AtomicInteger();
        BoundedPcmQueue q=new BoundedPcmQueue(32000,sink,()->{faults.incrementAndGet();signal.countDown();});
        short[] reused=pcm(1600,(short)11);
        try{
            check(q.offer(reused,1600,100),"first block accepted");waitFor(entered);
            for(int i=1;i<20;i++){Arrays.fill(reused,(short)(11+i));check(q.offer(reused,1600,100+i),"producer progresses while writer blocked");}
            Arrays.fill(reused,(short)99);
            check(q.acceptedSamples()==32000,"two-second accepted bound");
            check(q.highWaterSamples()==32000&&q.outstandingSamples()==32000,"inflight item charged to capacity");
            check(!q.offer(reused,1600,120),"overflow refused");waitFor(signal);
            check(!q.isAccepting()&&!q.isFinished(),"admission closed while persistence remains pending");
            check(!q.offer(reused,1600,121),"later callback rejected");
            check(q.acceptedSamples()==32000&&faults.get()==1,"rejection does not increase accepted count or duplicate signal");
        }finally{release.countDown();q.stopAdmission();}
        check(await(q)==null,"overflow drains without pretending storage failure");
        check(sink.contents.size()==20&&sink.finishes==1,"prefix drained and sealed once");
        check(sink.accepted==32000&&sink.observed==33600&&sink.overflow,"known rejected callback range retained");
        for(int i=0;i<20;i++){
            check(sink.contents.get(i)[0]==11+i&&sink.contents.get(i)[1599]==11+i,"deep copy and order");
            check(sink.times.get(i)==100+i,"original receive timestamp");
            check(zero(sink.retained.get(i)),"consumed owned buffer cleared");
        }
        check(q.outstandingSamples()==0,"no outstanding PCM after completion");
    }

    private static void stopDrainsAndDoesNotWaitForSink()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Recorder sink=new Recorder(){public void accept(short[] p,int n,long t)throws Exception{entered.countDown();waitFor(release);super.accept(p,n,t);}};
        AtomicInteger signals=new AtomicInteger();BoundedPcmQueue q=new BoundedPcmQueue(8,sink,signals::incrementAndGet);
        try{
            check(q.offer(pcm(2,(short)1),2,7),"prefix one");waitFor(entered);
            check(q.offer(pcm(2,(short)2),2,8),"prefix two");
            Thread stop=new Thread(q::stopAdmission,"stop-with-blocked-writer");stop.start();join(stop);
            q.stopAdmission();check(!q.offer(pcm(1,(short)3),1,9),"post-stop callback refused");
            check(!q.isFinished(),"stop is not durable completion");
        }finally{release.countDown();q.stopAdmission();}
        check(await(q)==null,"normal stop completion");
        check(sink.accepted==4&&sink.observed==4&&!sink.overflow&&sink.finishes==1,"frozen normal boundary");
        check(sink.contents.size()==2&&signals.get()==0,"normal stop preserves prefix without fault");
    }

    private static void failedWriterDiscardsTail()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),signal=new CountDownLatch(1);
        IOException injected=new IOException("synthetic write failure");
        RuntimeException callbackFailure=new RuntimeException("synthetic signal failure");
        Recorder sink=new Recorder(){
            public void accept(short[] p,int n,long t)throws Exception{super.accept(p,n,t);entered.countDown();waitFor(release);throw injected;}
            public void finish(long a,long o,boolean f,Exception e)throws Exception{check(signal.getCount()==0,"fault dispatched before recovery");super.finish(a,o,f,e);}
        };
        BoundedPcmQueue q=new BoundedPcmQueue(8,sink,()->{signal.countDown();throw callbackFailure;});
        try{
            check(q.offer(pcm(2,(short)4),2,1),"failing item admitted");waitFor(entered);
            check(q.offer(pcm(2,(short)5),2,2),"queued tail admitted");
        }finally{release.countDown();}
        check(await(q)==injected,"original persistence failure reported");
        check(injected.getSuppressed().length==1&&injected.getSuppressed()[0]==callbackFailure,"callback failure retained");
        check(sink.contents.size()==1&&sink.accepted==4&&sink.observed==4&&sink.failure==injected,"unprocessed tail accounted without write");
        check(zero(sink.retained.get(0))&&q.outstandingSamples()==0,"failure clears owned PCM");
        check(!q.offer(new short[1],1,3),"failure closes admission");
    }

    private static void faultSignalCompletesBeforeFinish()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),signalEntered=new CountDownLatch(1),signalRelease=new CountDownLatch(1);
        Recorder sink=new Recorder(){public void accept(short[] p,int n,long t)throws Exception{entered.countDown();waitFor(release);super.accept(p,n,t);}};
        BoundedPcmQueue q=new BoundedPcmQueue(1,sink,()->{signalEntered.countDown();try{waitFor(signalRelease);}catch(Exception e){throw new RuntimeException(e);}});
        AtomicReference<Throwable> error=new AtomicReference<>();
        Thread producer=new Thread(()->{try{check(!q.offer(new short[1],1,2),"overflow rejected");}catch(Throwable e){error.set(e);}},"overflow-producer");
        try{
            check(q.offer(new short[1],1,1),"capacity filled");waitFor(entered);producer.start();waitFor(signalEntered);
            release.countDown();q.stopAdmission();
            check(!q.isFinished(),"finalization waits for completed fault dispatch");
        }finally{release.countDown();signalRelease.countDown();q.stopAdmission();}
        join(producer);if(error.get()!=null)throw new AssertionError(error.get());
        check(await(q)==null&&sink.finishes==1,"completion after fault dispatch");
    }

    private static void callbackAndFinalizationFailures()throws Exception{
        RuntimeException callback=new RuntimeException("overflow callback");Recorder sink=new Recorder();
        BoundedPcmQueue q=new BoundedPcmQueue(1,sink,()->{throw callback;});
        check(!q.offer(new short[2],2,1),"oversized item refuses allocation");
        check(await(q)==callback,"overflow callback failure observable");
        check(sink.accepted==0&&sink.observed==2&&sink.overflow&&sink.contents.isEmpty(),"oversized observed range");
        IOException finishFailure=new IOException("seal failed");
        BoundedPcmQueue seal=new BoundedPcmQueue(1,new Recorder(){public void finish(long a,long o,boolean f,Exception e)throws Exception{throw finishFailure;}},()->{});
        seal.stopAdmission();check(await(seal)==finishFailure,"seal failure observable");
    }

    private static void overflowAndWriterFailureSignalOnce()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        IOException writeFailure=new IOException("write fails after overflow");
        RuntimeException callbackFailure=new RuntimeException("overflow stop dispatch failed");
        AtomicInteger signals=new AtomicInteger();
        Recorder sink=new Recorder(){
            public void accept(short[] p,int n,long t)throws Exception{super.accept(p,n,t);entered.countDown();waitFor(release);throw writeFailure;}
            public void finish(long a,long o,boolean f,Exception e)throws Exception{
                check(signals.get()==1,"combined fault dispatched exactly once before recovery");super.finish(a,o,f,e);
            }
        };
        BoundedPcmQueue q=new BoundedPcmQueue(2,sink,()->{signals.incrementAndGet();throw callbackFailure;});
        try{
            check(q.offer(pcm(2,(short)7),2,1),"combined fault initial item admitted");waitFor(entered);
            check(!q.offer(pcm(2,(short)8),2,2),"combined fault overflow rejected");
            check(signals.get()==1,"overflow signals while sink blocked");
        }finally{release.countDown();q.stopAdmission();}
        check(await(q)==writeFailure,"write failure remains primary");
        check(signals.get()==1,"subsequent write failure does not duplicate stop dispatch");
        check(writeFailure.getSuppressed().length==1&&writeFailure.getSuppressed()[0]==callbackFailure,"earlier callback exception preserved");
        check(sink.failure==writeFailure&&sink.accepted==2&&sink.observed==4&&sink.overflow,"combined failure boundaries retained");
        check(zero(sink.retained.get(0))&&q.outstandingSamples()==0,"combined failure releases PCM");
    }

    private static void interruptedWaiterRetainsOwnership()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),waiting=new CountDownLatch(1),returned=new CountDownLatch(1);
        Recorder sink=new Recorder(){public void accept(short[] p,int n,long t)throws Exception{entered.countDown();waitFor(release);super.accept(p,n,t);}};
        BoundedPcmQueue q=new BoundedPcmQueue(2,sink,()->{});
        AtomicReference<Exception> error=new AtomicReference<>();AtomicBoolean restored=new AtomicBoolean();
        Thread waiter=new Thread(()->{
            waiting.countDown();
            try{q.awaitFinished();}catch(Exception e){error.set(e);}
            finally{restored.set(Thread.currentThread().isInterrupted());returned.countDown();}
        },"interrupted-storage-owner-waiter");waiter.setDaemon(true);
        try{
            check(q.offer(pcm(2,(short)9),2,1),"interrupted waiter item admitted");waitFor(entered);q.stopAdmission();
            waiter.start();waitFor(waiting);waiter.interrupt();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while((waiter.getState()!=Thread.State.WAITING||waiter.isInterrupted())&&System.nanoTime()<deadline)Thread.yield();
            check(waiter.getState()==Thread.State.WAITING&&!waiter.isInterrupted(),"waiter resumed waiting after consuming interrupt");
            check(returned.getCount()==1&&!q.isFinished(),"interruption cannot release storage ownership before sink completes");
        }finally{release.countDown();q.stopAdmission();}
        join(waiter);check(returned.getCount()==0&&q.isFinished(),"waiter exits after storage completion");
        check(error.get() instanceof InterruptedException&&restored.get(),"interruption reported and status restored after safe join");
        check(sink.finishes==1&&sink.contents.size()==1,"interruption does not lose admitted prefix");
        check(await(q)==null,"separate waiter sees successful storage result");
    }

    private static void selfJoinAndValidation()throws Exception{
        AtomicReference<BoundedPcmQueue> ref=new AtomicReference<>();AtomicInteger selfJoin=new AtomicInteger();
        Recorder sink=new Recorder(){public void accept(short[] p,int n,long t)throws Exception{
            try{ref.get().awaitFinished();throw new AssertionError("self join allowed");}catch(IllegalStateException expected){selfJoin.incrementAndGet();}super.accept(p,n,t);
        }};
        BoundedPcmQueue q=new BoundedPcmQueue(8,sink,()->{});ref.set(q);
        check(q.offer(new short[1],1,10),"valid input");
        try{q.offer(new short[1],1,9);throw new AssertionError("backwards timestamp admitted");}catch(IllegalArgumentException expected){checks.incrementAndGet();}
        try{q.offer(new short[1],2,11);throw new AssertionError("invalid count admitted");}catch(IllegalArgumentException expected){checks.incrementAndGet();}
        q.stopAdmission();check(await(q)==null&&selfJoin.get()==1,"self join rejected without corrupting queue");
    }

    private static void concurrentStopPrefix()throws Exception{
        for(int run=0;run<200;run++){
            Recorder sink=new Recorder();AtomicInteger signals=new AtomicInteger();
            BoundedPcmQueue q=new BoundedPcmQueue(128,sink,signals::incrementAndGet);
            CountDownLatch start=new CountDownLatch(1),first=new CountDownLatch(1);
            AtomicInteger admitted=new AtomicInteger();AtomicReference<Throwable> error=new AtomicReference<>();
            Thread producer=new Thread(()->{try{waitFor(start);for(int i=0;i<100;i++){
                if(!q.offer(new short[]{(short)i},1,i))break;admitted.incrementAndGet();first.countDown();
            }}catch(Throwable e){error.set(e);}},"racing-producer");
            Thread stop=new Thread(()->{try{waitFor(first);q.stopAdmission();}catch(Throwable e){error.set(e);}},"racing-stop");
            producer.start();stop.start();start.countDown();join(producer);join(stop);q.stopAdmission();
            if(error.get()!=null)throw new AssertionError(error.get());check(await(q)==null,"race completed");
            check(sink.contents.size()==admitted.get()&&sink.accepted==admitted.get()&&sink.observed==admitted.get(),"race retained every admitted item");
            check(signals.get()==0&&sink.finishes==1&&!sink.overflow,"race has one normal finish");
            for(int i=0;i<sink.contents.size();i++)check(sink.contents.get(i)[0]==i&&sink.times.get(i)==i,"race prefix sequence");
            check(!q.offer(new short[1],1,100),"race late callback refused");
        }
    }


    private static List<short[]> pendingArrays(BoundedPcmQueue queue)throws Exception {
        Field list=BoundedPcmQueue.class.getDeclaredField("items"),lock=BoundedPcmQueue.class.getDeclaredField("lock");
        list.setAccessible(true);lock.setAccessible(true);List<short[]> result=new ArrayList<>();
        synchronized(lock.get(queue)){
            for(Object item:(Iterable<?>)list.get(queue)){
                Field pcm=item.getClass().getDeclaredField("pcm");pcm.setAccessible(true);result.add((short[])pcm.get(item));
            }
        }
        return result;
    }
    private static void privacyDiscardWhileWriterBlocked()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<short[]> inFlight=new AtomicReference<>();AtomicInteger closing=new AtomicInteger();
        AtomicLong clock=new AtomicLong(500);
        Recorder sink=new Recorder(){
            public void accept(short[] p,int n,long t)throws Exception{inFlight.set(p);entered.countDown();waitFor(release);super.accept(p,n,t);}
            public void closing(BoundedPcmQueue.Closed b){closing.incrementAndGet();}
        };
        BoundedPcmQueue q=new BoundedPcmQueue(8,sink,()->{},clock::get);
        List<short[]> pending;
        try{
            check(q.offer(pcm(2,(short)5),2,1),"privacy inflight admitted");waitFor(entered);
            check(q.offer(pcm(2,(short)6),2,2)&&q.offer(pcm(2,(short)7),2,3),"privacy queued prefix admitted");
            pending=pendingArrays(q);check(pending.size()==2,"privacy pending owners observed");
            q.requestStop("end",100,false);BoundedPcmQueue.Closed original=q.closedBoundary();
            clock.set(900);q.requestStop("privacy",700,true);
            check(q.closedBoundary()==original&&original.kind.equals("end")&&original.requestedNs==100&&original.acceptedNs==500,"first stop clocks immutable under discard upgrade");
            check(original.acceptedEnd==6&&original.observedEnd==6&&!original.overflow,"closed coverage excludes new callbacks");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(q.outstandingSamples()!=2&&System.nanoTime()<deadline)Thread.yield();
            check(q.isDiscarding()&&q.outstandingSamples()==2&&!q.isFinished(),"pending disposal is independent of blocked sink");
            for(short[] p:pending)check(zero(p),"discard clears each pending owned array");
            check(inFlight.get()[0]==5,"discard does not clear buffer still owned by sink");
            check(!q.offer(pcm(1,(short)8),1,4),"privacy rejects future input");
        }finally{release.countDown();q.stopAdmission();}
        check(await(q)==null&&closing.get()==1,"privacy closes once after sink ownership exits");
        check(sink.contents.size()==1&&sink.accepted==6,"privacy preserves accounting without draining pending data");
        check(zero(inFlight.get())&&q.outstandingSamples()==0,"consumer clears own inflight buffer");
    }
    private static void closingBeforeDrainAndFailureZeroing()throws Exception {
        for(boolean fail:new boolean[]{false,true}){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicInteger closes=new AtomicInteger();IOException injected=new IOException("control transaction failed");
            Recorder sink=new Recorder(){
                public void accept(short[] p,int n,long t)throws Exception{
                    if(contents.isEmpty()){entered.countDown();waitFor(release);}
                    else check(closes.get()==1,"durable-close callback before post-stop drain");
                    super.accept(p,n,t);
                }
                public void closing(BoundedPcmQueue.Closed b)throws Exception{
                    check(b.acceptedEnd==4&&b.observedEnd==4,"closing receives frozen range");
                    closes.incrementAndGet();if(fail)throw injected;
                }
            };
            BoundedPcmQueue q=new BoundedPcmQueue(4,sink,()->{});
            short[] second;
            try{
                check(q.offer(pcm(2,(short)1),2,1),"closing first item");waitFor(entered);
                check(q.offer(pcm(2,(short)2),2,2),"closing second item");second=pendingArrays(q).get(0);
                q.requestStop("pause",System.nanoTime(),false);
            }finally{release.countDown();q.stopAdmission();}
            check(await(q)==(fail?injected:null),"closing failure is reported");
            check(closes.get()==1&&sink.finishes==1,"closing and finish happen once");
            check(sink.contents.size()==(fail?1:2),"failed control transaction cannot drain more data");
            check(zero(second)&&q.outstandingSamples()==0,"dequeued item cleared even if closing fails");
        }
    }

    private static Thread consumer(BoundedPcmQueue queue)throws Exception {
        Field field=BoundedPcmQueue.class.getDeclaredField("consumer");field.setAccessible(true);return (Thread)field.get(queue);
    }
    private static void sameExceptionCompletion()throws Exception {
        for(boolean callback:new boolean[]{false,true}){
            RuntimeException original=new IllegalStateException("shared write failure");
            AtomicInteger signals=new AtomicInteger();AtomicReference<Throwable> uncaught=new AtomicReference<>();
            Recorder sink=new Recorder(){
                public void accept(short[] pcm,int count,long received)throws Exception{super.accept(pcm,count,received);throw original;}
                public void finish(long a,long o,boolean overflow,Exception failure)throws Exception{
                    super.finish(a,o,overflow,failure);check(failure==original,"finish keeps original exception identity");
                    if(!callback)throw original;
                }
            };
            BoundedPcmQueue q=new BoundedPcmQueue(2,sink,()->{signals.incrementAndGet();if(callback)throw original;});
            consumer(q).setUncaughtExceptionHandler((thread,error)->uncaught.set(error));
            check(q.offer(pcm(2,(short)7),2,1),"shared-failure input admitted");
            check(await(q)==original,"same exception from finish or signal cannot strand completion");
            check(original.getSuppressed().length==0&&uncaught.get()==null,"self-suppression never escapes the worker");
            check(signals.get()==1&&sink.finishes==1,"shared failure has one stop and one finish");
            check(q.outstandingSamples()==0&&zero(sink.retained.get(0)),"shared failure clears all owned plaintext");
        }
    }
    private static void errorCompletion()throws Exception {
        for(String phase:new String[]{"accept","closing","finish","signal","overflow_signal","adapter"}){
            AssertionError injected=new AssertionError("synthetic "+phase+" Error");
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicInteger signals=new AtomicInteger();AtomicReference<Throwable> uncaught=new AtomicReference<>();
            Recorder sink=new Recorder(){
                public void accept(short[] pcm,int count,long received)throws Exception{
                    super.accept(pcm,count,received);entered.countDown();waitFor(release);
                    if(phase.equals("accept")||phase.equals("signal")||phase.equals("adapter"))throw injected;
                }
                public void closing(BoundedPcmQueue.Closed boundary){if(phase.equals("closing"))throw injected;}
                public void finish(long a,long o,boolean overflow,Exception failure)throws Exception{
                    super.finish(a,o,overflow,failure);
                    if(phase.equals("finish"))throw injected;
                    check(failure!=null&&failure.getCause()==injected,"Error cause reaches finalization for "+phase);
                    if(phase.equals("adapter"))throw failure;
                }
            };
            BoundedPcmQueue q=new BoundedPcmQueue(4,sink,()->{
                signals.incrementAndGet();if(phase.equals("signal")||phase.equals("overflow_signal"))throw injected;
            });
            consumer(q).setUncaughtExceptionHandler((thread,error)->uncaught.set(error));
            short[] pending;
            try{
                check(q.offer(pcm(2,(short)8),2,1),"Error input admitted");waitFor(entered);
                check(q.offer(pcm(2,(short)9),2,2),"Error pending tail admitted");pending=pendingArrays(q).get(0);
                if(phase.equals("overflow_signal"))check(!q.offer(new short[1],1,3),"overflow callback Error is captured");
                else q.stopAdmission();
            }finally{release.countDown();q.stopAdmission();}
            Exception reported=await(q);
            check(reported!=null&&reported.getCause()==injected,"Error is a checked completion failure for existing cleanup callers");
            check(sink.finishes==1&&uncaught.get()==null,"Error cannot bypass finalization or escape to a process-wide handler");
            check(signals.get()==(phase.equals("finish")?0:1),"Error stop callback runs at most once");
            check(zero(pending)&&zero(sink.retained.get(0))&&q.outstandingSamples()==0,"Error clears pending and in-flight arrays");
            check(!q.isAccepting()&&injected.getSuppressed().length==0,"Error completion does not reopen admission or self-suppress adapter");
        }
    }
    private static void secondaryErrorsKeepPrimaryException()throws Exception {
        IOException primary=new IOException("primary write failure");
        AssertionError callback=new AssertionError("stop callback Error"),finish=new AssertionError("finalization Error");
        AtomicReference<Throwable> uncaught=new AtomicReference<>();
        Recorder sink=new Recorder(){
            public void accept(short[] pcm,int count,long received)throws Exception{super.accept(pcm,count,received);throw primary;}
            public void finish(long a,long o,boolean overflow,Exception failure)throws Exception{
                super.finish(a,o,overflow,failure);
                check(failure==primary&&failure.getSuppressed().length==1&&failure.getSuppressed()[0]==callback,"signal failure is visible before sealing decision");
                throw finish;
            }
        };
        BoundedPcmQueue q=new BoundedPcmQueue(1,sink,()->{throw callback;});
        consumer(q).setUncaughtExceptionHandler((thread,error)->uncaught.set(error));
        check(q.offer(new short[1],1,1),"secondary Error input");
        check(await(q)==primary,"ordinary Exception identity survives secondary Errors");
        check(primary.getSuppressed().length==2&&primary.getSuppressed()[0]==callback&&primary.getSuppressed()[1]==finish,"both secondary Errors remain available");
        check(sink.finishes==1&&uncaught.get()==null&&q.outstandingSamples()==0,"secondary Error still releases ownership");
    }
    private static void consumerInterruptionFinalizes()throws Exception {
        for(boolean duringAccept:new boolean[]{false,true}){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            AtomicInteger signals=new AtomicInteger();Recorder sink=new Recorder(){
                public void accept(short[] pcm,int count,long received)throws Exception{super.accept(pcm,count,received);entered.countDown();waitFor(release);}
            };
            BoundedPcmQueue q=new BoundedPcmQueue(4,sink,signals::incrementAndGet);Thread worker=consumer(q);
            if(duringAccept){check(q.offer(pcm(2,(short)3),2,1),"interrupt input admitted");waitFor(entered);check(q.offer(pcm(2,(short)4),2,2),"interrupt pending tail admitted");}
            try{worker.interrupt();Exception reported=await(q);
                check(reported instanceof InterruptedException&&sink.failure==reported,"consumer interrupt is reported to sink and caller");
                check(sink.finishes==1&&signals.get()==1&&!q.isAccepting()&&q.isDiscarding(),"consumer interrupt stops admission and finalizes once");
                check(worker.isInterrupted()&&q.outstandingSamples()==0,"consumer interrupt is restored only after ownership cleanup");
                if(duringAccept)check(zero(sink.retained.get(0)),"interrupted in-flight buffer cleared");
            }finally{release.countDown();q.stopAdmission();}
        }
    }
    private static void consumerInterruptWaitsForSignal()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),acceptReturned=new CountDownLatch(1),signalEntered=new CountDownLatch(1),signalRelease=new CountDownLatch(1);
        AtomicInteger finishCalls=new AtomicInteger();AtomicReference<Throwable> producerFailure=new AtomicReference<>();
        Recorder sink=new Recorder(){
            public void accept(short[] pcm,int count,long received)throws Exception{super.accept(pcm,count,received);entered.countDown();waitFor(release);acceptReturned.countDown();}
            public void finish(long a,long o,boolean overflow,Exception failure)throws Exception{
                check(signalRelease.getCount()==0,"consumer interrupt does not skip an active stop callback");finishCalls.incrementAndGet();super.finish(a,o,overflow,failure);
            }
        };
        BoundedPcmQueue q=new BoundedPcmQueue(1,sink,()->{signalEntered.countDown();try{waitFor(signalRelease);}catch(Exception error){throw new RuntimeException(error);}});
        Thread worker=consumer(q),producer=new Thread(()->{try{q.offer(new short[1],1,2);}catch(Throwable error){producerFailure.set(error);}},"interrupt-overflow-producer");
        try{
            check(q.offer(new short[1],1,1),"interrupt overflow input");waitFor(entered);producer.start();waitFor(signalEntered);
            release.countDown();waitFor(acceptReturned);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(worker.getState()!=Thread.State.WAITING&&System.nanoTime()<deadline)Thread.yield();
            check(worker.getState()==Thread.State.WAITING,"consumer waits for fault callback before interrupt");worker.interrupt();
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while((worker.getState()!=Thread.State.WAITING||worker.isInterrupted())&&System.nanoTime()<deadline)Thread.yield();
            check(worker.getState()==Thread.State.WAITING&&!worker.isInterrupted()&&!q.isFinished()&&finishCalls.get()==0,"interrupted consumer continues waiting for actual callback completion");
        }finally{release.countDown();signalRelease.countDown();q.stopAdmission();}
        join(producer);check(producerFailure.get()==null,"overflow producer completed");
        Exception reported=await(q);
        check(reported instanceof InterruptedException&&sink.failure==reported,"signal-wait interrupt is carried through finalization");
        check(finishCalls.get()==1&&worker.isInterrupted()&&q.outstandingSamples()==0,"signal-wait interrupt finishes and restores once after cleanup");
    }

    public static void main(String[] args){
        try{
            stalledWriterAndOverflow();stopDrainsAndDoesNotWaitForSink();failedWriterDiscardsTail();
            faultSignalCompletesBeforeFinish();callbackAndFinalizationFailures();overflowAndWriterFailureSignalOnce();
            interruptedWaiterRetainsOwnership();selfJoinAndValidation();concurrentStopPrefix();
            privacyDiscardWhileWriterBlocked();closingBeforeDrainAndFailureZeroing();
            sameExceptionCompletion();errorCompletion();secondaryErrorsKeepPrimaryException();
            consumerInterruptionFinalizes();consumerInterruptWaitsForSignal();
            System.out.println("PASS BoundedPcmQueue: "+checks.get()+" assertions; 200 stop races; synthetic JVM only");
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
