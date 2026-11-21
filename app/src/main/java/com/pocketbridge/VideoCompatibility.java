package com.pocketbridge;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.StatFs;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One encoder per sharing session; concurrent requests for a movie reuse its job. */
final class VideoCompatibility {
    private final Context context;
    private Job active;
    private boolean closed;

    VideoCompatibility(Context context) { this.context = context.getApplicationContext(); }

    static final class Job {
        final File output, partial;
        volatile boolean done;
        volatile String error;
        FFmpegSession encoder;
        Job(File directory, String id) {
            output = new File(directory, id + ".mp4");
            partial = new File(directory, id + "." + UUID.randomUUID() + ".partial");
        }
        File awaitComplete() throws Exception {
            while (!done) Thread.sleep(120);
            if (error != null) throw new IOException(error);
            return output;
        }
        InputStream openGrowing() throws Exception {
            while (!done) {
                if (partial.length() > 4096) {
                    try { return new FileInputStream(partial); }
                    catch (FileNotFoundException renamed) { /* finishing conversion */ }
                }
                Thread.sleep(100);
            }
            return new FileInputStream(awaitComplete());
        }
        void copyGrowing(InputStream input, OutputStream output) throws Exception {
            byte[] buffer = new byte[128 * 1024];
            while (true) {
                // Observe completion before reading so the final bytes are always drained.
                boolean finished = done;
                int n = input.read(buffer);
                if (n > 0) { output.write(buffer, 0, n); output.flush(); }
                else if (finished) { if (error != null) throw new IOException(error); return; }
                else Thread.sleep(80);
            }
        }
    }

    synchronized Job prepare(MediaRepository.Item item) throws Exception {
        if (closed) throw new IOException("Sharing has stopped.");
        File dir = new File(context.getCacheDir(), "browser-video");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Temporary storage is unavailable.");
        String key = item.key + ":" + item.size + ":" + item.modified;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder();
        for (byte b : digest) id.append(String.format(Locale.ROOT, "%02x", b & 255));
        Job job = new Job(dir,id.toString());
        if (job.output.isFile() && job.output.length()>0) {job.done=true;return job;}
        if (active != null && !active.done) {
            if (job.output.equals(active.output)) return active;
            throw new IOException("Another movie is being prepared. Retry after it finishes.");
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) {
            if (f.getName().endsWith(".mp4") || f.lastModified()<System.currentTimeMillis()-TimeUnit.DAYS.toMillis(1)) f.delete();
        }
        if (new StatFs(dir.getPath()).getAvailableBytes() < 96L*1024*1024) throw new IOException("Free some phone storage for compatibility playback.");
        active = job;
        Thread worker = new Thread(() -> convert(item,job),"PocketBridge-video");
        worker.start();
        return job;
    }

    private boolean hasAvcVideo(MediaRepository.Item item) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context,item.uri,null);
            for(int i=0;i<extractor.getTrackCount();i++) {
                String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("video/")) return "video/avc".equals(mime);
            }
        } catch(Exception ignored) { }
        finally {extractor.release();}
        return false;
    }

    private void convert(MediaRepository.Item item,Job job) {
        try {
            String input = "file".equals(item.uri.getScheme()) ? item.uri.getPath() : FFmpegKitConfig.getSafParameterForRead(context,item.uri);
            List<String> args=new ArrayList<>(Arrays.asList("-nostdin","-y","-i",input,"-map","0:v:0","-map","0:a:0?","-sn"));
            if(hasAvcVideo(item)) Collections.addAll(args,"-c:v","copy");
            else Collections.addAll(args,"-c:v","h264_mediacodec","-b:v","10M","-pix_fmt","yuv420p");
            Collections.addAll(args,"-c:a","aac","-b:a","160k","-movflags","+frag_keyframe+empty_moov+default_base_moof",
                    "-frag_duration","1000000","-flush_packets","1","-f","mp4",job.partial.getPath());
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            synchronized(this) {
                if(closed) throw new IOException("Sharing stopped.");
                job.encoder=FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),s -> done.countDown());
            }
            done.await();
            if (!ReturnCode.isSuccess(job.encoder.getReturnCode()) || job.partial.length()<1024)
                throw new IOException("This phone could not convert the video. Download the original to play it locally.");
            synchronized(this) {
                if(closed || !job.partial.renameTo(job.output)) throw new IOException("Could not save the playback copy.");
            }
        } catch(Exception | LinkageError e) {
            job.error = e.getMessage()==null ? "Video conversion failed." : e.getMessage();
        } finally { job.done=true; job.partial.delete(); }
    }

    synchronized void stop() {
        closed=true;
        if(active!=null && !active.done && active.encoder!=null) FFmpegKit.cancel(active.encoder.getSessionId());
    }
}
