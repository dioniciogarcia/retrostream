package com.tirso.tirsolador;
import android.content.Context;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** ROM leases survive process death as paths, while save directories are never purge targets. */
final class TemporaryRoms {
    private static final AtomicInteger sessions=new AtomicInteger();
    static boolean busy(){return sessions.get()>0;}
    static String begin(Context c,File source){String token=UUID.randomUUID().toString();sessions.incrementAndGet();c.getSharedPreferences("rom_leases",0).edit().putString(token,source.getAbsolutePath()).commit();return token;}
    static void finish(Context c,String token,File source){boolean removed=removeSource(c,source);if(token!=null){if(removed)c.getSharedPreferences("rom_leases",0).edit().remove(token).commit();sessions.decrementAndGet();}}
    static boolean removeSource(Context c,File file){
        if(file==null)return true;try{String p=file.getCanonicalPath();
            if(PhoneGames.isKept(c,file.getAbsolutePath())||PhoneGames.isKept(c,p))return true;   // phone app: kept on purpose
            boolean owned=p.startsWith(new File(c.getFilesDir(),"telegram/files").getCanonicalPath()+File.separator)||p.startsWith(c.getCacheDir().getCanonicalPath()+File.separator);return !owned||!file.exists()||file.delete();}catch(Exception e){return false;}
    }
    /** The game ended: the download stays for quick replays; it is removed when the app closes (see purge) or at the next start. */
    static void release(){sessions.decrementAndGet();}
    private static boolean swept;
    /** Removes every downloaded game that was left over from an earlier run of the app. */
    static void sweep(Context c){
        if(swept)return; swept=true;
        purge(c);
    }
    /** Removes the kept game files (the app is closing, or a previous run left them behind). */
    static void purge(Context c){
        if(busy())return;
        android.content.SharedPreferences p=c.getSharedPreferences("rom_leases",0);
        for(java.util.Map.Entry<String,?>e:p.getAll().entrySet())if(e.getValue() instanceof String&&removeSource(c,new File((String)e.getValue())))p.edit().remove(e.getKey()).commit();
        File[] files=c.getCacheDir().listFiles();if(files!=null)for(File f:files)if(f.getName().startsWith("play-")||f.getName().equals("roms"))ArchiveContent.removeTree(f);
        android.content.SharedPreferences old=c.getSharedPreferences("downloaded_roms",0);for(Object path:old.getAll().values())if(path instanceof String)removeSource(c,new File((String)path));old.edit().clear().apply();
    }
}
