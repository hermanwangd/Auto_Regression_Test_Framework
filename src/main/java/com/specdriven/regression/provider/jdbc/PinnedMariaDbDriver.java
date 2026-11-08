package com.specdriven.regression.provider.jdbc;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.Driver;
import java.util.Arrays;

/** Driver3.5.3 self-registers. Only a short-lived owned framework child may initialize it. */
public final class PinnedMariaDbDriver implements AutoCloseable {
    private final URLClassLoader loader;
    private final Driver driver;
    private PinnedMariaDbDriver(URLClassLoader loader,Driver driver){this.loader=loader;this.driver=driver;}
    public static void admit(Path path)throws java.io.IOException {
        require(path!=null&&path.isAbsolute()&&path.normalize().equals(path),"driver binding missing");
        Path cursor=path.getRoot();for(Path part:path){cursor=cursor.resolve(part);require(!Files.isSymbolicLink(cursor),"driver symlink rejected");}
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toRealPath().equals(path)&&Files.size(path)>0&&Files.size(path)<=1024*1024,"driver file invalid");
        Object links=Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS);require(links instanceof Number n&&n.longValue()==1,"driver hardlink rejected");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(1024*1024+1);require(bytes.length<=1024*1024&&ReviewedOwnedMariaDbPolicy.DRIVER_SHA.equals(ReviewedOwnedMariaDbPolicy.digest(bytes)),"driver digest mismatch");}
    }
    public static PinnedMariaDbDriver open(Path path)throws Exception {
        String invocation=System.getProperty("swarm.jdbc.invocation_id");
        require(invocation!=null&&invocation.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")&&java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Dswarm.jdbc.invocation_id="+invocation),"owned child invocation missing");
        admit(path);URLClassLoader loader=new URLClassLoader(new java.net.URL[]{path.toUri().toURL()},ClassLoader.getPlatformClassLoader());
        try {
            Driver driver=(Driver)Class.forName("org.mariadb.jdbc.Driver",true,loader).getDeclaredConstructor().newInstance();
            admit(path);return new PinnedMariaDbDriver(loader,driver);
        } catch(Throwable error) {try{loader.close();}catch(java.io.IOException ignored){}throw new IllegalArgumentException("reviewed_mariadb: pinned driver initialization failed");}
    }
    public Driver driver(){return driver;}
    @Override public void close()throws java.io.IOException {loader.close(); /* Not deregistration: parent must observe child exit/reap. */}
    private static void require(boolean ok,String message){ReviewedOwnedMariaDbPolicy.require(ok,message);}
}
