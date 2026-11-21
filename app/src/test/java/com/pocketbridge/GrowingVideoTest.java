package com.pocketbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class GrowingVideoTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void playsFirstFragmentAndDrainsFinalBytes() throws Exception {
        VideoCompatibility.Job job=new VideoCompatibility.Job(temp.getRoot(),"clip");
        byte[] initial=new byte[8192]; Arrays.fill(initial,(byte)41);
        CountDownLatch started=new CountDownLatch(1);
        ByteArrayOutputStream output=new ByteArrayOutputStream(){
            @Override public synchronized void write(byte[] b,int off,int len){super.write(b,off,len);started.countDown();}
        };
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(FileOutputStream writer=new FileOutputStream(job.partial)){
            writer.write(initial);
            try(InputStream reader=job.openGrowing()){
                Future<?> result=worker.submit(()->{job.copyGrowing(reader,output);return null;});
                assertTrue(started.await(3,TimeUnit.SECONDS));
                writer.write(42);writer.flush();job.done=true;
                result.get(3,TimeUnit.SECONDS);
                assertEquals(initial.length+1,output.size());assertEquals(42,output.toByteArray()[initial.length]);
            }
        } finally {job.done=true;worker.shutdownNow();}
    }
}
