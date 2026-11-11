package com.tirso.tirsolador;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.hardware.input.InputManager;
import android.media.*;
import android.os.Bundle;
import android.view.*;
import android.widget.Toast;
import java.io.*;
import java.util.concurrent.*;

/** One worker owns the core, audio, save operations and ROM preparation. */
public class GameActivity extends Activity implements InputManager.InputDeviceListener {
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final GameInput input=new GameInput();
    private ControllerProfiles profiles;
    private GameStorage storage;
    private Console console=Console.NDS;
    private File sourceRom;
    private String romLease;
    private boolean isDs=true;
    private volatile int frameWidth=256,frameHeight=384;
    private volatile double aspect=4.0/3.0;
    private long frameTime=16_715_113L;
    private Screen screen;
    private volatile boolean closed,foreground,ready,menu=true;
    private volatile int touchX=128,touchY=96;
    private volatile boolean pointerTouch,showFps;
    private long handle,nextFrame;
    private AudioTrack audio;
    private final int[] pixels=new int[1024*1024];
    private final short[] samples=new short[16384];
    private AlertDialog pauseDialog;
    private final java.util.List<AlertDialog> dialogs=new java.util.ArrayList<>();
    private String title,description;
    private InputManager manager;
    private int frames;
    private long fpsTime;
    private volatile float fps;
    @Override public void onCreate(Bundle state){
        T.init(this);
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        title=getIntent().getStringExtra("title");description=getIntent().getStringExtra("description");
        try{console=Console.valueOf(getIntent().getStringExtra("console"));}catch(Exception ignored){}isDs=console==Console.NDS;
        profiles=new ControllerProfiles(this);screen=new Screen();setContentView(screen);
        manager=(InputManager)getSystemService(INPUT_SERVICE);manager.registerInputDeviceListener(this,null);
        for(int id:InputDevice.getDeviceIds())registerGamepad(id);
        worker.execute(()->{
            try{
                String path=getIntent().getStringExtra("rom");if(path==null)throw new IOException(T.t("No se recibió la ROM"));
                File source=new File(path).getCanonicalFile();
                sourceRom=source;
                String root=getFilesDir().getCanonicalPath()+File.separator;
                String cache=getCacheDir().getCanonicalPath()+File.separator;
                if(!source.getPath().startsWith(root)&&!source.getPath().startsWith(cache))throw new IOException(T.t("Archivo fuera del almacenamiento de RetroStream"));
                String filename=getIntent().getStringExtra("filename");
                romLease=TemporaryRoms.begin(this,source);
                storage=new GameStorage(this,source,filename==null||filename.isEmpty()?source.getName():filename,console);
                if(closed)return;open();ready=true;menu=false;nextFrame=System.nanoTime();fpsTime=nextFrame;
                tick();
            }catch(Exception|LinkageError e){error(e,true);}
        });
    }
    private void open()throws Exception{
        if(isDs)handle=DsCore.open(storage.rom.getPath(),storage.battery().getPath());
        else{
            File system=new File(getFilesDir(),"bios");if(!system.isDirectory()&&!system.mkdirs())throw new IOException(T.t("No se pudo preparar la carpeta de archivos del sistema"));
            if(console==Console.MAME){File[] files=system.listFiles();if(files!=null)for(File file:files)if(file.getName().endsWith(".zip")){
                File link=new File(storage.workspace,file.getName());if(!link.exists())try{java.nio.file.Files.createLink(link.toPath(),file.toPath());}catch(Exception e){GameStorage.copyAtomic(file,link);}
            }}
            handle=RetroCore.open(CoreRegistry.library(this,console).getPath(),storage.rom.getPath(),storage.dir.getPath(),system.getPath(),CoreRegistry.key(console));
            updateVideoInfo();
        }
        if(audio==null){
            int size=Math.max(8192,AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT));
            audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setTransferMode(AudioTrack.MODE_STREAM).build();
            if(audio.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException(T.t("No se pudo iniciar el audio"));
        }
    }
    private void updateVideoInfo(){double[] info=RetroCore.info(handle);frameWidth=(int)info[0];frameHeight=(int)info[1];aspect=info[2]>0?info[2]:(double)frameWidth/frameHeight;frameTime=(long)(1_000_000_000.0/info[3]);}
    private byte[] snapshot()throws Exception{return isDs?DsCore.snapshot(handle):RetroCore.snapshot(handle);}
    private boolean restore(byte[] b)throws Exception{return isDs?DsCore.restore(handle,b):RetroCore.restore(handle,b);}
    private void flush()throws IOException{if(!(isDs?DsCore.flush(handle):RetroCore.flush(handle)))throw new IOException(T.t("No se pudo guardar la partida en la TV"));}
    private void closeCore(){if(handle!=0){if(isDs)DsCore.close(handle);else RetroCore.close(handle);handle=0;}}
    private void tick(){
        if(closed)return;
        try{
            if(!menu&&foreground&&handle!=0){
                if(audio.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING)audio.play();
                int count=isDs?DsCore.frame(handle,input.mask(),touchX,touchY,pointerTouch||input.touching(),pixels,samples):RetroCore.frame(handle,input.retroMasks(console==Console.N64),input.axes(),pixels,samples);
                if(!isDs)updateVideoInfo();
                if(count<0)throw new IOException(T.t("La consola se detuvo"));
                if(count>0)audio.write(samples,0,count,AudioTrack.WRITE_NON_BLOCKING);
                synchronized(screen.image){screen.image.setPixels(pixels,0,frameWidth,0,0,frameWidth,frameHeight);}
                screen.postInvalidate();
                long now=System.nanoTime();frames++;
                if(now-fpsTime>=1_000_000_000L){fps=frames*1_000_000_000f/(now-fpsTime);frames=0;fpsTime=now;}
                nextFrame+=frameTime;if(nextFrame<now-100_000_000L)nextFrame=now;
            }else{silence();nextFrame=System.nanoTime()+frameTime;fpsTime=System.nanoTime();frames=0;}
        }catch(Exception e){menu=true;silence();error(e,false);}
        if(!closed)worker.schedule(this::tick,Math.max(1,nextFrame-System.nanoTime()),TimeUnit.NANOSECONDS);
    }
    private void silence(){if(audio!=null&&audio.getPlayState()==AudioTrack.PLAYSTATE_PLAYING){audio.pause();audio.flush();}}
    private void resetInput(){input.clear();pointerTouch=false;}
    @Override protected void onResume(){super.onResume();foreground=true;if(ready)pauseMenu();}
    @Override protected void onPause(){foreground=false;if(ready){menu=true;worker.execute(()->{try{if(handle!=0)flush();}catch(Exception e){error(e,false);}});}resetInput();super.onPause();}
    @Override protected void onDestroy(){
        closed=true;resetInput();manager.unregisterInputDeviceListener(this);
        for(AlertDialog d:new java.util.ArrayList<>(dialogs))d.dismiss();
        worker.execute(()->{
            try{closeCore();}finally{
                if(audio!=null){audio.pause();audio.flush();audio.release();audio=null;}
                if(storage!=null)storage.cleanup();TemporaryRoms.finish(this,romLease,sourceRom);
                TirsoDrive.get(this).releaseRom(getIntent().getIntExtra("fileId",0),getIntent().getLongExtra("account",0));
            }
        });worker.shutdown();super.onDestroy();
    }
    private void error(Throwable e,boolean finish){runOnUiThread(()->{
        if(isFinishing()||isDestroyed())return;
        dialog().setTitle(console.title).setMessage(e.getMessage()==null?T.t("No se pudo ejecutar el juego"):e.getMessage())
            .setPositiveButton(finish?T.t("Volver"):T.t("Menú"),(d,w)->{if(finish)finish();else pauseMenu();}).setCancelable(false).show();
    });}
    private void task(Work work){worker.execute(()->{try{if(handle==0)throw new IOException(T.t("El juego todavía se está preparando"));work.run();}catch(Exception e){error(e,false);}});}
    private interface Work{void run()throws Exception;}
    private AlertDialog.Builder dialog(){return new AlertDialog.Builder(this){
        @Override public AlertDialog create(){AlertDialog d=super.create();dialogs.add(d);d.setOnDismissListener(x->dialogs.remove(d));return d;}
        @Override public AlertDialog show(){AlertDialog d=create();styleDialog(d);return d;}
    };}
    private void styleDialog(AlertDialog dialog){dialog.show();if(dialog.getWindow()!=null){android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(0xFF162133);background.setCornerRadius(24*getResources().getDisplayMetrics().density);dialog.getWindow().setBackgroundDrawable(background);dialog.getWindow().setDimAmount(.7f);}}
    private void toast(String text){runOnUiThread(()->Toast.makeText(this,text,Toast.LENGTH_LONG).show());}
    @Override public void onBackPressed(){pauseMenu();}
    private void pauseMenu(){
        if(closed||isFinishing()||(pauseDialog!=null&&pauseDialog.isShowing()))return;
        menu=true;resetInput();
        String[] options={T.t("Continuar"),T.t("Ver información del juego"),showFps?T.t("Ocultar FPS"):T.t("Mostrar FPS"),T.t("Guardar estado · Ranura 1"),T.t("Cargar estado · Ranura 1"),T.t("Respaldar partidas"),T.t("Restaurar partidas"),T.t("Reiniciar juego"),T.t("Salir del juego")};
        pauseDialog=dialog().setTitle(title).setItems(options,(d,which)->{
            d.dismiss();pauseDialog=null;
            switch(which){
                case 0:menu=false;break;
                case 1:dialog().setTitle(title).setMessage((description==null?"":description)+"\n\n"+(storage==null?console.title:storage.coreName)+T.t(" integrado · Resolución nativa · Audio estéreo\n")+(isDs?T.t("Stick derecho: cursor · R3: tocar\n"):"")+T.t("Cruceta y joysticks independientes\nPartidas locales · ROM temporal eliminada al salir"))
                    .setPositiveButton(T.t("Menú"),(x,w)->pauseMenu()).setOnCancelListener(x->pauseMenu()).show();break;
                case 2:showFps=!showFps;screen.invalidate();pauseMenu();break;
                case 3:task(()->{flush();storage.saveState(snapshot());toast(T.t("Estado guardado"));runOnUiThread(this::pauseMenu);});break;
                case 4:dialog().setTitle(T.t("¿Cargar el estado guardado?")).setMessage(T.t("Se reemplazará el progreso actual en memoria.")).setPositiveButton(T.t("Cargar"),(x,w)->task(()->{
                    if(!restore(storage.loadState()))throw new IOException(T.t("No se pudo cargar el estado"));resetInput();toast(T.t("Estado cargado"));runOnUiThread(this::pauseMenu);
                })).setNegativeButton(T.t("Cancelar"),(x,w)->pauseMenu()).setOnCancelListener(x->pauseMenu()).show();break;
                case 5:task(()->{flush();storage.saveBackupState(snapshot());runOnUiThread(()->chooseBackup(false));});break;
                case 6:dialog().setTitle(T.t("¿Restaurar un respaldo?")).setMessage(T.t("Reemplazará las partidas incluidas en el respaldo y reiniciará el juego.")).setPositiveButton(T.t("Elegir respaldo"),(x,w)->chooseBackup(true)).setNegativeButton(T.t("Cancelar"),(x,w)->pauseMenu()).setOnCancelListener(x->pauseMenu()).show();break;
                case 7:dialog().setTitle(T.t("¿Reiniciar juego?")).setPositiveButton(T.t("Reiniciar"),(x,w)->task(()->{flush();closeCore();open();resetInput();menu=false;})).setNegativeButton(T.t("Cancelar"),(x,w)->pauseMenu()).setOnCancelListener(x->pauseMenu()).show();break;
                case 8:task(()->{flush();runOnUiThread(this::finish);});break;
                default:break;
            }
        }).setOnCancelListener(d->{menu=false;resetInput();}).create();
        styleDialog(pauseDialog);
    }
    private void chooseBackup(boolean restore){
        Intent i=new Intent(restore?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
        if(!restore)i.putExtra(Intent.EXTRA_TITLE,"RetroStream-"+System.currentTimeMillis()+".zip");
        try{startActivityForResult(i,restore?22:21);}catch(android.content.ActivityNotFoundException e){error(new IOException(T.t("Esta TV no tiene selector de archivos. Instala un proveedor de documentos compatible para exportar o restaurar respaldos.")),false);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null){pauseMenu();return;}
        android.net.Uri uri=data.getData();
        if(request==21)task(()->{
            try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException(T.t("No se puede abrir el destino"));storage.backup(out);}
            toast(T.t("Respaldo creado"));runOnUiThread(this::pauseMenu);
        });
        else if(request==22)task(()->{
            flush();closeCore();
            try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException(T.t("No se puede abrir el respaldo"));storage.restore(in);}
            finally{open();}
            byte[] backup=storage.loadBackupState();if(backup!=null&&!restore(backup))throw new IOException(T.t("Se restauraron las partidas, pero no se pudo recuperar el estado de la sesión"));
            toast(T.t("Respaldo restaurado"));runOnUiThread(this::pauseMenu);
        });
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e){
        if(menu)return super.dispatchKeyEvent(e);
        boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
        if(e.getKeyCode()==KeyEvent.KEYCODE_BACK||e.getKeyCode()==KeyEvent.KEYCODE_BUTTON_MODE||e.getKeyCode()==KeyEvent.KEYCODE_MENU){if(down&&e.getRepeatCount()==0)pauseMenu();return true;}
        if(!gamepad(e.getDevice()))return super.dispatchKeyEvent(e);
        input.player(e.getDeviceId(),profiles.player(e.getDevice()));
        if(input.key(e.getDeviceId(),e.getKeyCode(),down))return true;
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent e){
        if(!menu&&e.isFromSource(InputDevice.SOURCE_JOYSTICK)&&e.getDevice()!=null){
            profiles.motion(e);ControllerProfiles.State p=profiles.state(e.getDeviceId());
            input.player(e.getDeviceId(),profiles.player(e.getDevice()));
            input.motion(e.getDeviceId(),p.hatX,p.hatY,p.leftX,p.leftY,profiles.deadzone(e.getDevice()));
            input.analog(e.getDeviceId(),p.leftX,p.leftY,p.rightX,p.rightY,profiles.deadzone(e.getDevice()));
            int left=e.getDevice().getMotionRange(MotionEvent.AXIS_LTRIGGER,e.getSource())!=null?MotionEvent.AXIS_LTRIGGER:MotionEvent.AXIS_BRAKE;
            int right=e.getDevice().getMotionRange(MotionEvent.AXIS_RTRIGGER,e.getSource())!=null?MotionEvent.AXIS_RTRIGGER:MotionEvent.AXIS_GAS;
            input.triggers(e.getDeviceId(),e.getAxisValue(left),e.getAxisValue(right));
            if(Math.abs(p.rightX)>.15||Math.abs(p.rightY)>.15){touchX=Math.max(0,Math.min(255,Math.round(128+p.rightX*127)));touchY=Math.max(0,Math.min(191,Math.round(96+p.rightY*95)));}
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
    private static boolean gamepad(InputDevice d){return d!=null&&((d.getSources()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||(d.getSources()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK);}
    private void registerGamepad(int id){InputDevice d=InputDevice.getDevice(id);if(gamepad(d))input.player(id,profiles.player(d));}
    public void onInputDeviceAdded(int id){registerGamepad(id);}
    public void onInputDeviceChanged(int id){input.remove(id);profiles.disconnect(id);registerGamepad(id);}
    public void onInputDeviceRemoved(int id){input.remove(id);profiles.disconnect(id);}
    final class Screen extends View {
        final Bitmap image=Bitmap.createBitmap(1024,1024,Bitmap.Config.ARGB_8888);
        final Paint paint=new Paint();final RectF top=new RectF(),bottom=new RectF();
        Screen(){super(GameActivity.this);setFocusable(true);setContentDescription(console.title);}
        @Override protected void onDraw(Canvas c){
            c.drawColor(Color.BLACK);float scale=Math.min((getWidth()-24)/512f,(getHeight()-32)/192f);float w=256*scale,h=192*scale;
            if(!ready){paint.setColor(Color.WHITE);paint.setTextSize(22);c.drawText(T.t("Preparando ")+console.title+"…",24,48,paint);return;}
            if(!isDs){
                float width=getWidth(),height=(float)(width/aspect);if(height>getHeight()){height=getHeight();width=(float)(height*aspect);}
                RectF destination=new RectF((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
                synchronized(image){c.drawBitmap(image,new Rect(0,0,frameWidth,frameHeight),destination,paint);}
                if(showFps){paint.setColor(0xFF77D8FF);paint.setTextSize(22);c.drawText(String.format(java.util.Locale.ROOT,"%.1f FPS",fps),16,26,paint);}return;
            }
            float x=(getWidth()-w*2-12)/2,y=(getHeight()-h)/2;top.set(x,y,x+w,y+h);bottom.set(x+w+12,y,x+w*2+12,y+h);
            synchronized(image){c.drawBitmap(image,new Rect(0,0,256,192),top,paint);c.drawBitmap(image,new Rect(0,192,256,384),bottom,paint);}
            paint.setColor(0xFF77D8FF);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);
            c.drawCircle(bottom.left+touchX/256f*bottom.width(),bottom.top+touchY/192f*bottom.height(),6,paint);paint.setStyle(Paint.Style.FILL);
            if(showFps){paint.setTextSize(22);c.drawText(String.format(java.util.Locale.ROOT,"%.1f FPS",fps),16,26,paint);}
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(menu||!isDs)return false;
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){pointerTouch=false;performClick();return true;}
            if(bottom.contains(e.getX(),e.getY())){touchX=Math.min(255,(int)((e.getX()-bottom.left)*256/bottom.width()));touchY=Math.min(191,(int)((e.getY()-bottom.top)*192/bottom.height()));pointerTouch=true;return true;}
            pointerTouch=false;return false;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
