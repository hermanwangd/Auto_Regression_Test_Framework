package com.featuredeliveryintelligence.fdi.producttest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

/** Strict one-frame anonymous-pipe protocol. Never include input data in diagnostics. */
public final class JdbcCredentialFrame {
    private static final byte[] MAGIC="SWJDBC1\n".getBytes(StandardCharsets.US_ASCII);
    private JdbcCredentialFrame() {}
    public static byte[] encode(String invocation,String handoff,String lease,char[] username,char[] password)throws IOException {
        identity(invocation,handoff,lease);
        try(var checked=new JdbcCredentialLease(username,password);var bytes=new WipingBuffer();var out=new DataOutputStream(bytes)) {
            out.write(MAGIC);field(out,invocation.toCharArray());field(out,handoff.toCharArray());field(out,lease.toCharArray());field(out,username);field(out,password);
            out.flush();byte[] frame=bytes.toByteArray();if(frame.length>1024)throw invalid();return frame;
        }
    }
    private static void field(DataOutputStream out,char[] chars)throws IOException {
        out.writeShort(chars.length);for(char c:chars){if(c<33||c>126)throw invalid();out.writeByte(c);}
    }
    public static JdbcCredentialLease read(InputStream input,String invocation,String handoff,String lease,long operationDeadline)throws Exception {
        identity(invocation,handoff,lease);
        return readDecoded(input,operationDeadline,()->decode(input,invocation,handoff,lease));
    }
    static JdbcCredentialLease readDecoded(InputStream input,long operationDeadline,Callable<JdbcCredentialLease> decoder)throws Exception {
        long end=Math.min(operationDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));
        if(end-System.nanoTime()<=0)throw new IllegalStateException("reviewed_mariadb: credential deadline");
        var ownership=new Ownership();
        FutureTask<JdbcCredentialLease> task=new FutureTask<>(()->{var value=decoder.call();ownership.offer(value);return value;});
        Thread worker=new Thread(task,"reviewed-jdbc-frame");worker.setDaemon(true);worker.start();
        boolean delivered=false;
        try {
            long remaining=end-System.nanoTime();if(remaining<=0)throw new TimeoutException();
            JdbcCredentialLease result=task.get(remaining,TimeUnit.NANOSECONDS);
            if(System.nanoTime()>=end)throw new TimeoutException();delivered=true;return result;
        } catch(TimeoutException e) {throw new IllegalStateException("reviewed_mariadb: credential deadline");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("reviewed_mariadb: credential interrupted");}
        catch(ExecutionException e) {
            if(e.getCause() instanceof IllegalArgumentException x && "reviewed_mariadb: credential identity mismatch".equals(x.getMessage())) throw new IllegalArgumentException(x.getMessage());
            throw invalid();
        } finally {
            // Abandon before cancellation or interruptible cleanup: late decoders must retain no lease.
            if(!delivered){ownership.abandon();task.cancel(true);worker.interrupt();}
            boolean interrupted=Thread.interrupted();var closeFailed=new java.util.concurrent.atomic.AtomicBoolean();
            Thread closer=new Thread(()->{try{input.close();}catch(Throwable ignored){closeFailed.set(true);}},"reviewed-jdbc-frame-close");closer.setDaemon(true);closer.start();
            try {closer.join(100);worker.join(100);}catch(InterruptedException ignored){interrupted=true;}
            boolean uncertain=worker.isAlive()||closer.isAlive()||closeFailed.get()||interrupted;
            if(!delivered||uncertain)ownership.abandon();else ownership.release();
            if(interrupted)Thread.currentThread().interrupt();
            if(uncertain)throw new IllegalStateException("reviewed_mariadb: frame cleanup unconfirmed");
        }
    }
    private static final class Ownership {
        private boolean abandoned;private JdbcCredentialLease lease;
        synchronized void offer(JdbcCredentialLease value){if(abandoned)value.close();else lease=value;}
        synchronized void abandon(){abandoned=true;if(lease!=null){lease.close();lease=null;}}
        synchronized void release(){lease=null;}
    }

    private static JdbcCredentialLease decode(InputStream input,String invocation,String handoff,String lease)throws IOException {
        byte[] bytes=null;char[] user=null,password=null;
        try {
            bytes=input.readNBytes(1025);if(bytes.length>1024)throw invalid();
            var in=new DataInputStream(new ByteArrayInputStream(bytes));if(!Arrays.equals(in.readNBytes(MAGIC.length),MAGIC))throw invalid();
            String actualInvocation=new String(chars(in,36,36));String actualHandoff=new String(chars(in,64,64));String actualLease=new String(chars(in,64,64));
            if(!invocation.equals(actualInvocation)||!handoff.equals(actualHandoff)||!lease.equals(actualLease))throw new IllegalArgumentException("reviewed_mariadb: credential identity mismatch");
            user=chars(in,1,64);password=chars(in,32,128);if(in.read()!=-1)throw invalid();return new JdbcCredentialLease(user,password);
        } catch(IOException e) {throw invalid();}
        finally {if(bytes!=null)Arrays.fill(bytes,(byte)0);if(user!=null)Arrays.fill(user,'\0');if(password!=null)Arrays.fill(password,'\0');}
    }
    private static char[] chars(DataInputStream input,int min,int max)throws IOException {
        int n=input.readUnsignedShort();if(n<min||n>max)throw invalid();char[] out=new char[n];
        try {for(int i=0;i<n;i++){int b=input.readUnsignedByte();if(b<33||b>126)throw invalid();out[i]=(char)b;}return out;}
        catch(RuntimeException|IOException e){Arrays.fill(out,'\0');throw e;}
    }
    private static void identity(String invocation,String handoff,String lease) {
        if(invocation==null||!invocation.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||handoff==null||!handoff.matches("[a-f0-9]{64}")||lease==null||!lease.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("reviewed_mariadb: credential identity invalid");
    }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        @Override public void close(){Arrays.fill(buf,(byte)0);reset();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("reviewed_mariadb: invalid credential frame");}
}
