package com.tirso.tirsolador;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MainActivity extends Activity {
    private Library library;
    private Artwork artwork;
    private LinearLayout root, body, content;
    private ControllerProfiles controllers;
    private android.hardware.input.InputManager inputManager;
    private android.hardware.input.InputManager.InputDeviceListener inputListener;
    private ScrollView activeScroll;
    private int libraryScroll;
    private String libraryFocus = "";
    private TirsoDrive drive;
    private TirsoDrive.Listener driveListener;
    private TextView downloadLabel,downloadButton;
    private TextView catalogStatus;
    private android.widget.ProgressBar playProgress;
    private String pendingPlay="";
    private int visibleGames=60;
    private android.graphics.Bitmap qrBitmap;
    private String renderedQr="";
    private String lastDriveError="";
    private int text, muted, surface, background, accent, strokeColor;
    private Typeface fontRegular, fontBold;
    private boolean pixel;
    private String page = "home", query = "";
    private Console console;
    private Game selected;
    private boolean favorites;
    private String lastFocus = "";
    private int mode;
    private LinearLayout infoPanel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        T.init(this);
        TemporaryRoms.sweep(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        library = new Library(this); artwork = new Artwork(this); controllers = new ControllerProfiles(this);
        drive=TirsoDrive.get(this);
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        inputListener=new android.hardware.input.InputManager.InputDeviceListener(){
            public void onInputDeviceAdded(int id) { }
            public void onInputDeviceChanged(int id) { controllers.disconnect(id); }
            public void onInputDeviceRemoved(int id) { controllers.disconnect(id); }
        };
        inputManager.registerInputDeviceListener(inputListener,null);
        if (state != null) {
            page = state.getString("page", "home"); query = state.getString("query", "");
            favorites = state.getBoolean("favorites"); lastFocus = state.getString("focus", "");
            libraryFocus=state.getString("libraryFocus","");libraryScroll=state.getInt("libraryScroll",0);
            try { console = Console.valueOf(state.getString("console", "")); } catch (IllegalArgumentException ignored) { }
            for (Game g : library.games) if (g.id.equals(state.getString("game"))) selected = g;
        }
        if(!drive.onboarded()&&!drive.skipped())page="welcome";
        render();
        driveListener=catalog->{
            if(catalog){rememberLibrary();library=new Library(this);}
            if(!drive.skipped()&&(drive.stage==TirsoDrive.Stage.QR||drive.stage==TirsoDrive.Stage.PASSWORD||drive.stage==TirsoDrive.Stage.CODE))page="welcome";
            if(page.equals("welcome")||catalog)render();
            else if(page.equals("loading"))updateDownload();
            else if(!drive.error.isEmpty()&&!drive.error.equals(lastDriveError))Toast.makeText(this,drive.error,Toast.LENGTH_LONG).show();
            lastDriveError=drive.error;
            if(catalogStatus!=null&&page.equals("library"))catalogStatus.setText(catalogMessage());
        };
        drive.attach(driveListener);drive.start();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("page", page); state.putString("query", query); state.putBoolean("favorites", favorites);
        state.putString("console", console == null ? "" : console.name());
        state.putString("game", selected == null ? "" : selected.id); state.putString("focus", lastFocus);
        rememberLibrary();state.putString("libraryFocus",libraryFocus);state.putInt("libraryScroll",libraryScroll);
    }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    // ---------------------------------------------------------------- design system (drawn in 1920x1080 units)
    private float u = 1f;
    private String th = "midnight";
    private boolean mono;
    private Typeface fHead, fBody, fMono, fWord;
    private int dp(float n) { return Math.round(n * 2f * u); }
    private int px(float n) { return Math.round(n * u); }
    private Typeface font(int id) { try { return getResources().getFont(id); } catch (Exception e) { return null; } }
    private String themeKey() { String t = library.theme(); return t.equals("GBA clásico") ? "gba" : t.equals("Mosaico") ? "tiles" : t.equals("Consola completa") ? "console" : "midnight"; }
    private void applyTheme() {
        th = themeKey(); mono = !th.equals("midnight");
        switch (th) {
            case "gba": background = 0xFFF1EFE9; surface = Color.WHITE; text = 0xFF1A1A2E; muted = 0xFF5A5A70; accent = 0xFF7A4DFF; strokeColor = 0xFF1A1A2E; break;
            case "tiles": background = 0xFF1C2447; surface = 0xFF2A3560; text = Color.WHITE; muted = 0xFFC8D1F5; accent = Color.WHITE; strokeColor = 0x33FFFFFF; break;
            case "console": background = 0xFFF4F1EC; surface = Color.WHITE; text = 0xFF1A1A2E; muted = 0xFF6B6B7A; accent = 0xFFE5382B; strokeColor = 0xFF1A1A2E; break;
            default: background = 0xFF07101F; surface = 0x12FFFFFF; text = 0xFFF4F7FC; muted = 0xFF8FA3BF; accent = 0xFF2DE2C8; strokeColor = 0x1FFFFFFF;
        }
        fBody = font(R.font.inter_regular);
        fMono = font(R.font.dejavu_mono_bold);
        fHead = mono ? fMono : font(R.font.poppins_bold);
        fWord = (th.equals("tiles") || th.equals("console")) ? font(R.font.poppins_bolditalic) : mono ? fMono : font(R.font.poppins_bold);
        fontRegular = fBody; fontBold = fHead; pixel = false;
    }
    private TextView tv(String s, float size, int color, Typeface face) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u); t.setTextColor(color);
        if (face != null) t.setTypeface(face); t.setIncludeFontPadding(false); return t;
    }
    private TextView label(String s, int size, int color, boolean bold) {
        TextView t = tv(s, size * 2f, color, bold ? fHead : fBody); t.setPadding(0, dp(3), 0, dp(3)); return t;
    }
    private GradientDrawable panel(int color, boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color);
        g.setCornerRadius(px(th.equals("midnight") ? 26 : th.equals("tiles") ? 30 : 22));
        int ring = focused ? (th.equals("midnight") ? accent : th.equals("tiles") ? Color.WHITE : strokeColor) : strokeColor;
        g.setStroke(Math.max(1, px(focused ? 5 : th.equals("midnight") || th.equals("tiles") ? 1.5f : 3.5f)), ring);
        return g;
    }
    private void focusable(View v, String key, int color, Runnable click) { focusableWith(v, key, f -> panel(color, f), click); }
    private TextView button(String title, String key, Runnable click) {
        TextView t = label(title, 14, text, true); t.setGravity(Gravity.CENTER);
        t.setPadding(px(30), px(14), px(30), px(14)); focusable(t, key, surface, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, px(84)); p.setMargins(px(8), px(8), px(8), px(8));
        t.setLayoutParams(p); return t;
    }
    private Drawable iconBg(boolean f) {
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL);
        g.setColor(th.equals("midnight") ? 0x14FFFFFF : th.equals("tiles") ? 0x22FFFFFF : 0x00000000);
        if (f) g.setStroke(px(4), th.equals("midnight") ? accent : th.equals("tiles") ? Color.WHITE : strokeColor);
        else if (th.equals("midnight")) g.setStroke(px(1.5f), 0x24FFFFFF);
        return g;
    }
    private View icon(String name, String label, String key, Runnable click) {
        IconView v = new IconView(this, name, label, text); focusableWith(v, key, this::iconBg, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(60), px(60)); p.setMargins(px(7), 0, px(7), 0);
        v.setLayoutParams(p); return v;
    }
    private GradientDrawable round(int color, float radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(px(radius)); return g; }
    private GradientDrawable gradient(int a, int b, float radius, GradientDrawable.Orientation o) {
        GradientDrawable g = new GradientDrawable(o, new int[]{a, b}); g.setCornerRadius(px(radius)); return g;
    }
    private View spacer() { return new View(this); }
    private int hsv(float h, float s, float v) { return Color.HSVToColor(new float[]{((h % 360) + 360) % 360, s, v}); }
    private int[] gameColors(Game g) {
        float h = Math.abs(g.title.hashCode()) % 360;
        return new int[]{hsv(h, .66f, .96f), hsv(h + 38, .74f, .88f)};
    }
    private String coreName(Console c) {
        switch (c) {
            case N64: return "Mupen64Plus-Next"; case NDS: return "melonDS"; case GBA: case GB: case GBC: return "mGBA";
            case SNES: return "Snes9x"; case NES: return "FCEUmm"; case MD: return "Genesis Plus GX"; default: return "MAME 2003-Plus";
        }
    }
    private String heroSubtitle(Console c) {
        switch (c) {
            case N64: return "NINTENDO 64"; case GBA: return "GAME BOY ADVANCE"; case GBC: return "GAME BOY COLOR"; case GB: return "GAME BOY";
            case NDS: return "NINTENDO DS"; case SNES: return "SUPER NINTENDO"; case NES: return "ENTERTAINMENT SYSTEM"; case MD: return "MEGA DRIVE";
            default: return T.english() ? "ARCADE" : "ARCADE";
        }
    }
    private boolean logoMode() { return getSharedPreferences("ui", MODE_PRIVATE).getBoolean("logos", false); }

    private TextView pill(String s, int size, int color, int bg) {
        TextView t = tv(s, size, color, fBody); t.setPadding(px(22), px(9), px(22), px(9)); t.setBackground(round(bg, 40)); return t;
    }
    private View keyChip(String key, String name) {
        LinearLayout row = horizontal();
        if (mono) {
            row.addView(tv("● ", 22, text, fMono)); row.addView(tv((name).toUpperCase(java.util.Locale.ROOT), 22, text, fMono));
            ((TextView) row.getChildAt(1)).setLetterSpacing(.12f);
        } else {
            TextView k = tv(key, 22, text, fHead); k.setGravity(Gravity.CENTER); k.setPadding(px(18), px(6), px(18), px(6)); k.setBackground(round(0x1FFFFFFF, 12));
            row.addView(k); TextView n = tv(name, 24, muted, fBody); n.setPadding(px(12), 0, px(26), 0); row.addView(n);
        }
        if (mono) row.setPadding(0, 0, px(44), 0);
        return row;
    }
    private void footer(String[][] hints, String note) {
        LinearLayout f = horizontal(); f.setPadding(0, px(14), 0, 0);
        for (String[] h : hints) f.addView(keyChip(h[0], h[1]));
        f.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
        if (note != null && !mono) { TextView n = tv(note, 22, muted, fBody); n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.END); f.addView(n, new LinearLayout.LayoutParams(-2, -2)); }
        root.addView(f, new LinearLayout.LayoutParams(-1, -2));
    }
    private LinearLayout buildHeader(String monoTitle, String monoRight) {
        LinearLayout h = horizontal(); h.setPadding(0, 0, 0, px(14));
        android.widget.TextClock clock = new android.widget.TextClock(this); clock.setFormat24Hour("HH:mm"); clock.setFormat12Hour("h:mm a");
        clock.setTextColor(text); clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, (mono ? 30 : 34) * u); if (fHead != null) clock.setTypeface(fHead); clock.setIncludeFontPadding(false);
        boolean welcome = page.equals("welcome");
        if (!mono) {
            View mark = new View(this); mark.setBackground(gradient(0xFF2DE2C8, 0xFF2A7BFF, 13, GradientDrawable.Orientation.TL_BR));
            h.addView(mark, new LinearLayout.LayoutParams(px(46), px(46)));
            TextView name = tv("RetroStream", 34, text, fHead); name.setPadding(px(20), 0, 0, 0); h.addView(name);
            h.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
            boolean ok = drive.stage == TirsoDrive.Stage.READY;
            TextView st = tv("●  " + (ok ? T.t("TirsoDrive · conectado") : T.t("TirsoDrive · sin conectar")), 26, muted, fBody);
            st.setPadding(px(26), px(14), px(26), px(14));
            SpannableString sp = new SpannableString(st.getText()); sp.setSpan(new ForegroundColorSpan(ok ? 0xFF2DE2C8 : 0xFFFFB84D), 0, 1, 0); st.setText(sp);
            focusableWith(st, "nav_drive", f -> { GradientDrawable g = round(0x0FFFFFFF, 40); g.setStroke(px(f ? 4 : 1.5f), f ? accent : 0x24FFFFFF); return g; }, this::connectScreen);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.rightMargin = px(18); h.addView(st, sl);
            if (!welcome) {
                if (page.equals("library")) { h.addView(icon("sort", T.t("Ordenar juegos: ") + (library.descending() ? "Z a A" : "A a Z"), "sort", this::sortGames)); h.addView(icon("refresh", T.t("Refrescar catálogo"), "refresh", this::refreshCatalog)); }
                h.addView(icon("search", T.t("Buscar juegos"), "search", this::search));
                h.addView(icon("star", T.t("Favoritos"), "nav_favorites", this::openFavorites));
                h.addView(icon("settings", T.t("Ajustes"), "nav_settings", () -> route("settings")));
            }
            clock.setPadding(px(22), 0, 0, 0); h.addView(clock);
        } else {
            h.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));
            TextView title = tv(monoTitle, 28, text, fMono); title.setLetterSpacing(.14f); title.setGravity(Gravity.CENTER); title.setSingleLine(true);
            h.addView(title, new LinearLayout.LayoutParams(0, -2, 2));
            LinearLayout right = horizontal(); right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            TextView r = tv(monoRight, 28, text, fMono); r.setLetterSpacing(.14f); r.setPadding(0, 0, px(14), 0); right.addView(r);
            if (!welcome) {
                right.addView(icon("search", T.t("Buscar juegos"), "search", this::search));
                right.addView(icon("star", T.t("Favoritos"), "nav_favorites", this::openFavorites));
                right.addView(icon("settings", T.t("Ajustes"), "nav_settings", () -> route("settings")));
            }
            h.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return h;
    }
    private void openFavorites() { console = null; favorites = true; mode = 0; query = ""; libraryFocus = ""; libraryScroll = 0; route("library"); }

    private void rememberLibrary() {
        if(page.equals("library")) {
            libraryScroll=activeScroll==null?0:activeScroll.getScrollY();
            if(lastFocus.startsWith("game_"))libraryFocus=lastFocus;
        }
    }
    private void cancelPending(){if(!pendingPlay.isEmpty()&&selected!=null&&pendingPlay.equals(selected.id)){org.drinkless.tdlib.TdApi.File f=drive.file(selected);if(f!=null&&f.local.isDownloadingActive)drive.cancelDownload(selected);}pendingPlay="";}
    private void route(String target) { cancelPending();rememberLibrary();page = target; lastFocus = ""; render(); }
    private void backToLibrary() {cancelPending();page="library";lastFocus=libraryFocus;render();}
    private void render() {
        u = getResources().getDisplayMetrics().widthPixels / 1920f;
        applyTheme();
        root = vertical();
        int sidePad = px(mono ? 80 : 70);
        root.setPadding(sidePad, px(mono ? 38 : 34), sidePad, px(mono ? 30 : 28));
        if (th.equals("midnight")) root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0C2442, 0xFF07101F, 0xFF07101F, 0xFF0A2A33}));
        else root.setBackgroundColor(background);
        setContentView(root);
        content = null; activeScroll = null; catalogStatus = null; infoPanel = null;
        String monoTitle, monoRight;
        int systems = 0; for (Console c : Console.values()) if (library.visible(c)) systems++;
        switch (page) {
            case "library": monoTitle = favorites ? T.t("FAVORITOS") : console == null ? T.t("BIBLIOTECA") : console.title.toUpperCase(java.util.Locale.ROOT);
                monoRight = library.filtered(console, favorites || mode == 1, query).size() + T.t(" JUEGOS"); break;
            case "settings": monoTitle = T.t("AJUSTES"); monoRight = ""; break;
            case "welcome": monoTitle = "TIRSODRIVE"; monoRight = ""; break;
            case "loading": monoTitle = T.t("CARGANDO"); monoRight = ""; break;
            default:
                monoTitle = T.t("CONSOLAS");
                monoRight = th.equals("console") ? (heroIndex() + 1) + T.t(" DE ") + systems : systems + T.t(" SISTEMAS");
        }
        if (!page.equals("loading")) root.addView(buildHeader(monoTitle, monoRight));
        body = vertical(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page.equals("welcome")) welcome();
        else if (page.equals("settings")) settings();
        else if (page.equals("loading") && selected != null) loadingScreen();
        else if (page.equals("library")) libraryScreen();
        else home();
        if (page.equals("home")) footer(new String[][]{{"A", T.t("Abrir")}, {"Y", T.t("Buscar")}, {"X", T.t("Favoritos")}, {"Menu", T.t("Ajustes")}}, T.t("Los juegos se descargan solo al jugar · ROM eliminada al salir"));
        else if (page.equals("library")) footer(new String[][]{{"A", T.t("Jugar")}, {"B", T.t("Volver")}, {"Y", T.t("Buscar")}, {"X", T.t("Favorito")}}, T.t("Carátulas de libretro · guardadas en la TV"));
        else if (page.equals("settings") || page.equals("welcome")) footer(new String[][]{{"A", T.t("Elegir")}, {"B", T.t("Volver")}}, null);
        root.post(() -> {
            View target = lastFocus.isEmpty() ? null : root.findViewWithTag(lastFocus);
            if (target == null) target = firstContentFocusable(content == null ? body : content);
            if (target != null) target.requestFocus();
            if (page.equals("library") && activeScroll != null) activeScroll.scrollTo(0, libraryScroll);
        });
    }
    private View firstContentFocusable(View v) {
        if (v.isFocusable()) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = firstContentFocusable(((ViewGroup) v).getChildAt(i)); if (f != null) return f;
        }
        return null;
    }
    private void connectScreen(){page="welcome";lastFocus="";drive.enterConnection();render();}
    private void welcome(){
        heading(drive.stage==TirsoDrive.Stage.READY?T.t("Tu biblioteca, conectada"):T.t("Tu biblioteca retro, conectada"),drive.status);
        if(!drive.error.isEmpty())body.addView(label(drive.error,13,library.dark()?0xFFFFADB5:0xFFB0263D,false));
        if(drive.stage==TirsoDrive.Stage.READY){
            content=scrollContent();
            for(Console c:Console.values()){
                TirsoDrive.Source source=drive.sources.get(c);
                content.addView(button(c.title+"  ·  "+(source.chatId==0?source.issue:source.title),"source_"+c.name(),()->chooseSource(c)));
            }
            if(drive.discovering||drive.syncing)content.addView(button(T.t("Cancelar actualización"),"cancel_sync",drive::cancelSync));
            else{
                content.addView(button(T.t("Buscar grupos"),"discover_sources",drive::discover));
                content.addView(button(T.t("Actualizar catálogo"),"sync_sources",drive::sync));
                content.addView(button(T.t("Ir a mi biblioteca"),"open_connected_library",()->{drive.finishOnboarding();library=new Library(this);route("home");}));
            }
            content.addView(button(T.t("Cerrar sesión"),"logout",()->new AlertDialog.Builder(this).setTitle(T.t("Cerrar sesión de Telegram"))
                .setMessage(T.t("Se quitará la biblioteca sincronizada de esta cuenta en la TV. Podrás volver a conectar."))
                .setPositiveButton(T.t("Cerrar sesión"),(d,w)->drive.logout()).setNegativeButton(T.t("Cancelar"),null).show()));return;
        }
        content=scrollContent();
        LinearLayout columns=horizontal();columns.setGravity(Gravity.TOP);
        LinearLayout instructions=vertical();instructions.setPadding(0,dp(10),dp(20),0);
        instructions.addView(label(T.t("1  Conectar    2  Encontrar consolas    3  Biblioteca"),12,accent,true));
        instructions.addView(label(T.t("Abre Telegram en tu teléfono y entra en Ajustes → Dispositivos → Vincular dispositivo."),17,text,false));
        instructions.addView(label(T.t("Después buscaremos tus grupos TD - ROMs, incluidos los archivados, y asignaremos cada uno a su consola."),14,muted,false));
        if(drive.stage==TirsoDrive.Stage.CONFIG){
            instructions.addView(button(T.t("Configurar TirsoDrive"),"configure_credentials",this::configureCredentials));
            instructions.addView(label(T.t("Configuración inicial de la aplicación. Tu sesión de Telegram se autoriza después con el QR."),12,muted,false));
        }else if(drive.stage==TirsoDrive.Stage.PASSWORD)instructions.addView(button(T.t("Introducir contraseña"),"auth_password",()->authInput(true)));
        else if(drive.stage==TirsoDrive.Stage.CODE)instructions.addView(button(T.t("Introducir código"),"auth_code",()->authInput(false)));
        else instructions.addView(button(T.t("Reintentar conexión"),"retry_connection",drive::retry));
        if(!drive.error.isEmpty()&&drive.stage!=TirsoDrive.Stage.CONFIG)instructions.addView(button(T.t("Revisar configuración"),"repair_credentials",this::configureCredentials));
        instructions.addView(button(T.t("Continuar sin conexión"),"continue_offline",()->{drive.skip();route("home");}));
        columns.addView(instructions,new LinearLayout.LayoutParams(0,-2,1));
        if(drive.stage==TirsoDrive.Stage.QR&&!drive.qrLink.isEmpty()){
            try{
                if(!drive.qrLink.equals(renderedQr)){qrBitmap=QrCode.encode(drive.qrLink,480);renderedQr=drive.qrLink;}
                ImageView qr=new ImageView(this);qr.setImageBitmap(qrBitmap);qr.setContentDescription(T.t("Código QR para vincular Telegram"));
                qr.setScaleType(ImageView.ScaleType.FIT_CENTER);qr.setBackgroundColor(Color.WHITE);
                columns.addView(qr,new LinearLayout.LayoutParams(dp(230),dp(230)));
            }catch(Exception e){columns.addView(label(T.t("No se pudo dibujar el QR. Reintenta la conexión."),14,muted,false));}
        }
        content.addView(columns);
    }
    private void configureCredentials(){
        EditText hash=new EditText(this);hash.setSingleLine(true);hash.setHint(T.t("API hash de TirsoDrive"));
        hash.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(T.t("Configurar TirsoDrive"))
            .setMessage("API ID: "+BuildConfig.TELEGRAM_API_ID+T.t(". Introduce el API hash una sola vez; se guarda cifrado en esta TV."))
            .setView(hash).setPositiveButton(T.t("Guardar"),(d,w)->{String value=hash.getText().toString().trim();hash.setText("");drive.configureHash(value);})
            .setNegativeButton(T.t("Cancelar"),(d,w)->hash.setText("")).show();
    }
    private void authInput(boolean password){
        EditText input=new EditText(this);input.setSingleLine(true);
        input.setInputType(password?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle(password?T.t("Verificación en dos pasos"):T.t("Código de Telegram")).setView(input)
            .setPositiveButton(T.t("Continuar"),(d,w)->{String value=input.getText().toString();input.setText("");if(password)drive.password(value);else drive.code(value);})
            .setNegativeButton(T.t("Cancelar"),(d,w)->input.setText("")).show();
    }
    private void chooseSource(Console console){
        if(drive.syncing||drive.discovering)return;
        java.util.List<org.drinkless.tdlib.TdApi.Chat> choices=drive.choices(console);
        String[] labels=new String[choices.size()+1];labels[0]=T.t("Sin grupo asignado");
        for(int i=0;i<choices.size();i++)labels[i+1]=choices.get(i).title+" · ID "+choices.get(i).id;
        new AlertDialog.Builder(this).setTitle(console.title).setItems(labels,(d,w)->{
            if(w==0)drive.unbind(console);else drive.bind(console,choices.get(w-1).id);
        }).setNegativeButton(T.t("Cancelar"),null).show();
    }
    private LinearLayout scrollContent() {
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true);activeScroll=scroll;
        content = vertical(); content.setPadding(0, dp(10), 0, dp(8)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); return content;
    }
    private ImageView logo(Console c) {
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(c.title);
        int id = getResources().getIdentifier("logo_" + c.logo, "drawable", getPackageName());
        if (id != 0) image.setImageResource(id);
        image.setPadding(dp(14), dp(9), dp(14), dp(9)); return image;
    }
    private String gameCount(int count) { return count+ (count==1?T.t(" juego"):T.t(" juegos")); }
    private String themeName(){String t=library.theme();return t.equals("GBA clásico")||t.equals("Mosaico")||t.equals("Consola completa")?t:"Moderno";}
    private boolean hasGamepad(){
        for(int id:InputDevice.getDeviceIds()){InputDevice d=InputDevice.getDevice(id);if(d!=null&&((d.getSources()&InputDevice.SOURCE_GAMEPAD)==InputDevice.SOURCE_GAMEPAD||(d.getSources()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK))return true;}
        return false;
    }
    private int brand(Console c){
        switch(c){
            case N64:return 0xFF2F6DF6;case GBA:return 0xFF7A4DFF;case GBC:return 0xFFFF9A2E;case GB:return 0xFF4DB36B;case NDS:return 0xFFFF4D73;
            case SNES:return 0xFFA06BFF;case NES:return 0xFFFF4040;case MD:return 0xFF2AA8FF;default:return 0xFF14C9AA;
        }
    }
    private int shade(int color,float f){return 0xFF000000|((int)(((color>>16)&255)*f)<<16)|((int)(((color>>8)&255)*f)<<8)|(int)((color&255)*f);}
    private int blend(int a,int b,float t){
        int r=(int)(((a>>16)&255)*t+((b>>16)&255)*(1-t)),g=(int)(((a>>8)&255)*t+((b>>8)&255)*(1-t)),bl=(int)((a&255)*t+(b&255)*(1-t));
        return 0xFF000000|(r<<16)|(g<<8)|bl;
    }
    private void focusableWith(View v,String key,java.util.function.Function<Boolean,android.graphics.drawable.Drawable> bg,Runnable click){
        v.setTag(key);v.setFocusable(true);v.setClickable(true);v.setBackground(bg.apply(false));v.setOnClickListener(x->click.run());
        v.setOnFocusChangeListener((x,has)->{x.setBackground(bg.apply(has));if(has)lastFocus=key;x.setElevation(dp(has?12:0));x.animate().scaleX(has?1.05f:1f).scaleY(has?1.05f:1f).setDuration(140).start();});
    }
    private void heading(String title, String subtitle) {
        TextView t = tv(title, mono ? 58 : 60, text, fHead); t.setPadding(0, px(6), 0, 0); body.addView(t);
        TextView s = tv(subtitle, 26, muted, fBody); s.setPadding(0, px(10), 0, px(14)); body.addView(s);
    }
    private int onAccent() { return th.equals("midnight") ? 0xFF04121F : th.equals("gba") || th.equals("console") ? Color.WHITE : 0xFF1C2447; }
    private TextView primary(String title, String key, Runnable click) {
        TextView t = tv(title, 30, onAccent(), fHead); t.setGravity(Gravity.CENTER); t.setPadding(px(40), px(20), px(40), px(20));
        focusableWith(t, key, focused -> {
            GradientDrawable g = th.equals("midnight") ? gradient(0xFF2DE2C8, 0xFF2A9BFF, 24, GradientDrawable.Orientation.LEFT_RIGHT) : round(th.equals("tiles") ? 0xFFFFC857 : 0xFF1A1A2E, th.equals("tiles") ? 26 : 18);
            if (focused) g.setStroke(px(5), th.equals("midnight") ? Color.WHITE : th.equals("tiles") ? Color.WHITE : accent);
            return g;
        }, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, px(92)); p.setMargins(px(4), px(6), px(4), px(6)); t.setLayoutParams(p); return t;
    }
    private String wordmark(Console c) { return c.name(); }
    private int heroIndex() {
        java.util.ArrayList<Console> v = new java.util.ArrayList<>(); for (Console c : Console.values()) if (library.visible(c)) v.add(c);
        if (v.isEmpty()) return 0; int i = v.indexOf(heroConsole(v)); return Math.max(0, i);
    }
    private Drawable tileBg(Console c, boolean focused) {
        int brand = brand(c);
        if (th.equals("gba")) {
            GradientDrawable face = round(focused ? brand : Color.WHITE, 28); face.setStroke(px(focused ? 5 : 3.5f), 0xFF1A1A2E);
            if (!focused) return face;
            GradientDrawable shadow = round(0xFF1A1A2E, 28);
            android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new Drawable[]{shadow, face});
            l.setLayerInset(0, px(14), px(14), 0, 0); l.setLayerInset(1, 0, 0, px(14), px(14)); return l;
        }
        if (th.equals("tiles")) {
            GradientDrawable g = gradient(blend(0xFFFFFFFF, brand, .14f), shade(brand, .82f), 34, GradientDrawable.Orientation.TL_BR);
            if (focused) g.setStroke(px(7), Color.WHITE); return g;
        }
        int base = 0xFF0E1A2C;
        GradientDrawable g = gradient(blend(brand, base, focused ? .42f : .24f), base, 30, GradientDrawable.Orientation.TL_BR);
        g.setStroke(px(focused ? 4 : 1.5f), focused ? accent : 0x1FFFFFFF); return g;
    }
    private View consoleTile(Console c) {
        LinearLayout tile = vertical(); tile.setGravity(Gravity.CENTER); tile.setPadding(px(20), px(16), px(20), px(24));
        boolean gba = th.equals("gba");
        View mark;
        if (logoMode()) {
            ImageView logo = logo(c); logo.setColorFilter(gba ? 0xFF1A1A2E : Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            mark = logo; tile.addView(mark, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            TextView wm = tv(wordmark(c), th.equals("midnight") ? 90 : th.equals("tiles") ? 124 : 92, gba ? 0xFF1A1A2E : Color.WHITE, fWord);
            wm.setGravity(Gravity.CENTER); wm.setSingleLine(true);
            if (th.equals("midnight")) wm.setShadowLayer(px(30), 0, 0, brand(c));
            else if (th.equals("tiles")) wm.setShadowLayer(px(2), 0, px(7), 0x55000000);
            tile.addView(wm, new LinearLayout.LayoutParams(-1, 0, 1)); mark = wm;
            if (!gba) wm.setGravity(Gravity.CENTER);
        }
        TextView name = null;
        if (th.equals("midnight")) { name = tv(c.title, 27, text, fBody); name.setGravity(Gravity.CENTER); name.setSingleLine(true); tile.addView(name, new LinearLayout.LayoutParams(-1, -2)); }
        String countText = gameCount(library.count(c)); if (mono) countText = countText.toUpperCase(java.util.Locale.ROOT);
        TextView count = tv(countText, th.equals("tiles") ? 27 : 23, th.equals("tiles") ? 0xE6FFFFFF : muted, mono ? fMono : fBody);
        count.setGravity(Gravity.CENTER); if (mono) count.setLetterSpacing(.12f);
        if (th.equals("midnight")) { count.setPadding(px(24), px(8), px(24), px(8)); count.setBackground(round(0x66000000, 40)); }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.gravity = Gravity.CENTER_HORIZONTAL; cp.topMargin = px(12); tile.addView(count, cp);
        tile.setContentDescription(c.title + ", " + library.count(c) + T.t(" juegos"));
        focusableWith(tile, "console_" + c.name(), focused -> tileBg(c, focused), () -> openConsole(c));
        if (gba) {
            View.OnFocusChangeListener base = tile.getOnFocusChangeListener();
            tile.setOnFocusChangeListener((v, has) -> {
                base.onFocusChange(v, has);
                if (mark instanceof TextView) ((TextView) mark).setTextColor(has ? Color.WHITE : 0xFF1A1A2E);
                count.setTextColor(has ? 0xFFEDE6FF : muted); v.setScaleX(1f); v.setScaleY(1f);
            });
        }
        return tile;
    }
    private void home() {
        java.util.ArrayList<Console> visible = new java.util.ArrayList<>();
        for (Console c : Console.values()) if (library.visible(c)) visible.add(c);
        if (visible.isEmpty()) {
            content = scrollContent(); content.addView(label(T.t("No hay consolas visibles"), 20, text, true));
            content.addView(button(T.t("Elegir consolas"), "empty_settings", () -> route("settings"))); return;
        }
        if (th.equals("console")) { heroHome(visible); return; }
        if (th.equals("midnight")) {
            TextView t = tv(T.t("Tu biblioteca retro"), 62, text, fHead); t.setPadding(0, px(18), 0, 0); body.addView(t);
            int games = 0; for (Console c : visible) games += library.count(c);
            TextView s = tv(visible.size() + T.t(" consolas · ") + games + T.t(" juegos desde Telegram"), 28, muted, fBody); s.setPadding(0, px(10), 0, px(22)); body.addView(s);
        }
        int cols = th.equals("midnight") ? 5 : 3, gap = px(th.equals("midnight") ? 38 : 30);
        content = vertical(); content.setPadding(0, th.equals("midnight") ? 0 : px(12), 0, 0); body.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        int rows = (visible.size() + cols - 1) / cols;
        LinearLayout row = null; int index = 0;
        for (Console c : visible) {
            if (index % cols == 0) { row = horizontal(); content.addView(row, new LinearLayout.LayoutParams(-1, 0, 1)); }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1); p.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            row.addView(consoleTile(c), p); index++;
        }
        while (index % cols != 0) { row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1)); index++; }
    }
    private void openConsole(Console c) {
        console = c; library.homeConsole(c); favorites = false; mode = 0; query = ""; libraryFocus = ""; libraryScroll = 0; visibleGames = 60; route("library");
        drive.refresh(c);
    }
    private Console heroConsole(java.util.List<Console> consoles) {
        for (Console c : consoles) if (c.name().equals(library.homeConsole())) return c; return consoles.get(0);
    }
    private void moveHero(int delta) {
        java.util.ArrayList<Console> consoles = new java.util.ArrayList<>(); for (Console c : Console.values()) if (library.visible(c)) consoles.add(c);
        if (consoles.isEmpty()) return;
        int index = consoles.indexOf(heroConsole(consoles));
        library.homeConsole(consoles.get((index + delta + consoles.size()) % consoles.size())); lastFocus = "hero_console"; render();
    }
    private void heroHome(java.util.List<Console> consoles) {
        Console current = heroConsole(consoles);
        if (lastFocus.isEmpty()) lastFocus = "hero_console";
        content = vertical(); body.addView(content, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout hero = vertical(); hero.setGravity(Gravity.CENTER); hero.setPadding(px(40), px(30), px(40), px(30));
        if (logoMode()) { ImageView hl = logo(current); hl.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN); hero.addView(hl, new LinearLayout.LayoutParams(px(900), px(360))); }
        else { TextView wm = tv(wordmark(current), 330, Color.WHITE, fWord); wm.setGravity(Gravity.CENTER); wm.setSingleLine(true); wm.setShadowLayer(px(2), 0, px(8), 0x33000000); hero.addView(wm, new LinearLayout.LayoutParams(-2, -2)); }
        TextView sub = tv(heroSubtitle(current), 46, brand(current), fMono); sub.setLetterSpacing(.06f); sub.setPadding(px(34), px(10), px(34), px(10)); sub.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.topMargin = px(10); hero.addView(sub, sp);
        TextView info = tv(gameCount(library.count(current)) + " · " + (drive.sources.get(current) != null && drive.sources.get(current).chatId != 0 ? drive.sources.get(current).title : TelegramCatalog.expectedTitle(current)), 32, 0xE6FFFFFF, fBody);
        info.setPadding(0, px(30), 0, 0); hero.addView(info);
        hero.setContentDescription(current.title + ", " + library.count(current) + T.t(" juegos. Abrir biblioteca"));
        focusableWith(hero, "hero_console", f -> { GradientDrawable g = gradient(blend(0xFFFFFFFF, brand(current), .12f), shade(brand(current), .78f), 58, GradientDrawable.Orientation.TL_BR); if (f) g.setStroke(px(7), 0xFF1A1A2E); return g; }, () -> openConsole(current));
        hero.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { moveHero(-1); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { moveHero(1); return true; }
            return false;
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, 0, 1); hp.bottomMargin = px(24); content.addView(hero, hp);
        LinearLayout strip = horizontal(); strip.setGravity(Gravity.CENTER);
        for (Console c : consoles) {
            boolean on = c == current;
            TextView chip = tv(c.name(), 27, on ? Color.WHITE : 0xFF6B6B5A, fMono); chip.setLetterSpacing(.1f); chip.setGravity(Gravity.CENTER);
            focusableWith(chip, "chip_" + c.name(), f -> { GradientDrawable g = round(on ? brand(c) : 0xFFE4DFD6, 20); if (f) g.setStroke(px(5), 0xFF1A1A2E); return g; }, () -> { library.homeConsole(c); lastFocus = "hero_console"; render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(120), px(78)); cp.setMargins(px(8), 0, px(8), 0); strip.addView(chip, cp);
        }
        content.addView(strip, new LinearLayout.LayoutParams(-1, -2));
    }
    private String catalogMessage(){
        if(drive.stage!=TirsoDrive.Stage.READY)return T.t("Conecta TirsoDrive para actualizar tu colección");
        if(drive.syncing||drive.discovering)return T.t("Actualizando tu colección…");
        if(!drive.error.isEmpty())return drive.error;
        if(console!=null&&drive.sources.get(console).chatId==0)return T.t("Vincula ")+TelegramCatalog.expectedTitle(console)+T.t(" en TirsoDrive");
        return T.t("Actualizada automáticamente");
    }
    private void libraryScreen() {
        List<Game> games;
        if (mode == 2) {
            games = new java.util.ArrayList<>(); java.util.List<String> ids = library.recent();
            for (String id : ids) for (Game g : library.games) if (g.id.equals(id) && (console == null || g.console == console) && library.visible(g.console)
                    && g.title.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) games.add(g);
        } else games = library.filtered(console, favorites || mode == 1, query);
        boolean gba = th.equals("gba");
        LinearLayout split = horizontal(); split.setGravity(Gravity.TOP); body.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout left = vertical(); split.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        // title row
        LinearLayout titleRow = horizontal(); titleRow.setPadding(0, 0, 0, px(14));
        LinearLayout titles = vertical();
        String cname = console == null ? (favorites ? T.t("Favoritos") : T.t("Biblioteca")) : console.title;
        if (gba) {
            TextView code = tv(console == null ? "ALL" : console.name(), 24, accent, fMono); code.setLetterSpacing(.1f); titles.addView(code);
        }
        String src = console != null && drive.sources.get(console) != null && drive.sources.get(console).chatId != 0 ? drive.sources.get(console).title : "";
        TextView sub = tv(games.size() + T.t(" juegos · ") + (library.descending() ? "Z–A" : "A–Z") + (src.isEmpty() ? "" : T.t(" · de «") + src + "»") + (query.isEmpty() ? "" : " · " + query), 24, muted, fBody);
        sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (!gba) {
            LinearLayout nameRow = horizontal();
            if (console != null && !mono) { TextView wm = tv(console.name(), 58, brand(console), fWord); wm.setPadding(0, 0, px(22), 0); nameRow.addView(wm); }
            nameRow.addView(tv(cname, 52, text, fHead)); titles.addView(nameRow);
        } else { TextView big = tv(games.isEmpty() ? cname : games.get(0).title, 46, text, fHead); big.setSingleLine(true); big.setEllipsize(android.text.TextUtils.TruncateAt.END); titles.addView(big); libBigTitle = big; }
        titles.addView(sub); titleRow.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        String[] names = {T.t("Todos"), T.t("Favoritos"), T.t("Recientes")};
        for (int i = 0; i < 3; i++) {
            final int m = i; boolean on = (favorites ? 1 : mode) == i;
            TextView chip = tv(names[i], 25, on ? onAccent() : text, fBody); chip.setGravity(Gravity.CENTER); chip.setPadding(px(30), px(12), px(30), px(12));
            focusableWith(chip, "mode_" + i, f -> { GradientDrawable g = round(on ? (th.equals("midnight") ? accent : th.equals("tiles") ? Color.WHITE : 0xFF1A1A2E) : (th.equals("midnight") ? 0x14FFFFFF : 0x22000000), 40); if (f) g.setStroke(px(4), th.equals("midnight") ? Color.WHITE : accent); return g; },
                () -> { favorites = false; mode = m; libraryScroll = 0; libraryFocus = ""; lastFocus = "mode_" + m; render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, px(64)); cp.leftMargin = px(12); titleRow.addView(chip, cp);
        }
        left.addView(titleRow);
        catalogStatus = tv(catalogMessage(), 20, muted, fBody); catalogStatus.setPadding(0, 0, 0, px(10)); left.addView(catalogStatus);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        LinearLayout grid = vertical(); grid.setPadding(px(8), px(8), px(8), px(24)); scroll.addView(grid);
        left.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = grid;
        int columns = gba ? 3 : 5; int gap = px(gba ? 26 : 24);
        int panelW = px(gba ? 600 : 560);
        int gridW = getResources().getDisplayMetrics().widthPixels - 2 * px(mono ? 80 : 70) - panelW - px(40) - px(16);
        int cardW = (gridW - columns * gap) / columns; int cardH = gba ? Math.round(cardW * .8f) : cardW;
        LinearLayout row = null; int index = 0;
        for (Game game : games.subList(0, Math.min(visibleGames, games.size()))) {
            if (index % columns == 0) { row = horizontal(); row.setGravity(Gravity.TOP); grid.addView(row); }
            LinearLayout cell = vertical(); cell.setPadding(0, 0, 0, px(10));
            FrameLayout art = new FrameLayout(this);
            int[] gc = gameColors(game);
            art.setBackground(gradient(gc[0], gc[1], gba ? 22 : 26, GradientDrawable.Orientation.TL_BR));
            TextView ph = tv(game.title, gba ? 28 : 30, Color.WHITE, fHead); ph.setGravity(Gravity.CENTER); ph.setPadding(px(14), px(14), px(14), px(14)); ph.setMaxLines(4); ph.setEllipsize(android.text.TextUtils.TruncateAt.END);
            art.addView(ph, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription(game.title);
            art.addView(image, new FrameLayout.LayoutParams(-1, -1)); artwork.load(game, image);
            cell.addView(art, new LinearLayout.LayoutParams(cardW, cardH));
            if (!gba) {
                TextView name = tv((library.favorite(game) ? "★ " : "") + game.title, 24, text, fBody); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(px(4), px(10), px(4), 0);
                cell.addView(name, new LinearLayout.LayoutParams(cardW, -2));
            }
            cell.setPadding(px(6), px(6), px(6), px(6));
            final int ringColor = th.equals("midnight") ? accent : th.equals("tiles") ? Color.WHITE : 0xFF1A1A2E;
            focusableWith(cell, "game_" + game.id, f -> { GradientDrawable g = round(0, 30); if (f) g.setStroke(px(6), ringColor); return g; }, () -> startPlay(game));
            View.OnFocusChangeListener base = cell.getOnFocusChangeListener();
            cell.setOnFocusChangeListener((v, has) -> { base.onFocusChange(v, has); v.setScaleX(1f); v.setScaleY(1f); if (has) showInfo(game); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(gap / 2, gap / 4, gap / 2, gap / 4); row.addView(cell, p); index++;
        }
        if (index == 0) grid.addView(tv(favorites || mode == 1 ? T.t("Guarda juegos con X para verlos aquí.") : mode == 2 ? T.t("Aún no has jugado a nada.") : T.t("No hay juegos que coincidan."), 30, muted, fBody));
        if (games.size() > visibleGames) grid.addView(button(T.t("Mostrar más juegos"), "load_more", () -> { int next = visibleGames; visibleGames += 60; lastFocus = "game_" + games.get(next).id; render(); }));
        // right panel
        infoPanel = vertical(); infoPanel.setPadding(px(32), px(32), px(32), px(32));
        if (gba) { GradientDrawable g = round(Color.WHITE, 26); g.setStroke(px(4), 0xFF1A1A2E); infoPanel.setBackground(g); }
        else infoPanel.setBackground(round(th.equals("midnight") ? 0x0FFFFFFF : 0x22000000, 34));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(panelW, -1); ip.leftMargin = px(40); split.addView(infoPanel, ip);
        Game first = games.isEmpty() ? null : games.get(0);
        if (!libraryFocus.isEmpty()) for (Game g : games) if (("game_" + g.id).equals(libraryFocus)) first = g;
        if (first != null) showInfo(first);
    }
    private TextView libBigTitle;
    private void showInfo(Game game) {
        if (infoPanel == null) return;
        infoPanel.removeAllViews(); focusedGame = game;
        if (libBigTitle != null) libBigTitle.setText(game.title);
        boolean gba = th.equals("gba");
        int[] gc = gameColors(game);
        FrameLayout art = new FrameLayout(this); art.setBackground(gradient(gc[0], gc[1], 28, GradientDrawable.Orientation.TL_BR));
        TextView ph = tv(game.title, 36, Color.WHITE, fHead); ph.setGravity(Gravity.CENTER); ph.setPadding(px(20), px(20), px(20), px(20)); art.addView(ph, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); art.addView(image, new FrameLayout.LayoutParams(-1, -1)); artwork.load(game, image);
        infoPanel.addView(art, new LinearLayout.LayoutParams(-1, px(gba ? 250 : 340)));
        TextView title = tv(game.title, 38, gba ? 0xFF1A1A2E : text, fHead); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setPadding(0, px(22), 0, 0); infoPanel.addView(title);
        String size = game.fileSize > 0 ? android.text.format.Formatter.formatFileSize(this, game.fileSize) : "—";
        TextView meta = tv(game.console.title + " · " + size, 24, gba ? 0xFF5A5A70 : muted, fBody); meta.setPadding(0, px(8), 0, px(10)); infoPanel.addView(meta);
        TextView desc = tv(game.description, 24, gba ? 0xFF3A3A50 : muted, fBody); desc.setMaxLines(4); desc.setEllipsize(android.text.TextUtils.TruncateAt.END); infoPanel.addView(desc, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout chips = horizontal(); chips.setPadding(0, px(10), 0, px(14));
        chips.addView(pill(T.t("Tamaño ") + size, 22, gba ? 0xFF1A1A2E : text, gba ? 0x14000000 : 0x14FFFFFF));
        TextView core = pill(T.t("Núcleo ") + coreName(game.console), 22, gba ? 0xFF1A1A2E : text, gba ? 0x14000000 : 0x14FFFFFF);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = px(12); chips.addView(core, cp);
        infoPanel.addView(chips);
        LinearLayout actions = horizontal();
        TextView play = primary(T.t("▶  Jugar"), "panel_play", () -> startPlay(game));
        actions.addView(play, new LinearLayout.LayoutParams(0, px(92), 1));
        TextView star = tv(library.favorite(game) ? "★" : "☆", 40, gba ? 0xFF1A1A2E : text, fHead); star.setGravity(Gravity.CENTER);
        focusableWith(star, "panel_fav", f -> { GradientDrawable g = round(gba ? 0x14000000 : 0x14FFFFFF, 26); if (f) g.setStroke(px(5), th.equals("midnight") ? accent : gba ? 0xFF1A1A2E : Color.WHITE); return g; }, () -> { library.toggleFavorite(game); libraryFocus = "game_" + game.id; lastFocus = "panel_fav"; render(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(92), px(92)); sp.leftMargin = px(12); actions.addView(star, sp);
        infoPanel.addView(actions);
    }
    private void startPlay(Game game) {
        selected = game; drive.inspectFile(game); playSelected();
    }
    private void loadingScreen() {
        boolean gba = th.equals("gba");
        LinearLayout center = vertical(); center.setGravity(Gravity.CENTER_HORIZONTAL); body.addView(center, new LinearLayout.LayoutParams(-1, -1));
        TextView wm = tv(selected.console.name(), 66, brand(selected.console), fWord); wm.setPadding(0, px(10), 0, px(22)); center.addView(wm);
        int[] gc = gameColors(selected);
        FrameLayout art = new FrameLayout(this); art.setBackground(gradient(gc[0], gc[1], 30, GradientDrawable.Orientation.TL_BR));
        TextView ph = tv(selected.title, 44, Color.WHITE, fHead); ph.setGravity(Gravity.CENTER); ph.setPadding(px(30), px(30), px(30), px(30)); art.addView(ph, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); art.addView(image, new FrameLayout.LayoutParams(-1, -1)); artwork.load(selected, image);
        center.addView(art, new LinearLayout.LayoutParams(px(600), px(330)));
        TextView t = tv(selected.title, 40, text, fHead); t.setPadding(0, px(22), 0, 0); center.addView(t);
        loadingTitle = tv(T.t("Cargando…"), 30, muted, fBody); loadingTitle.setPadding(0, px(6), 0, px(18)); center.addView(loadingTitle);
        playProgress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); playProgress.setMax(1000);
        GradientDrawable track = round(th.equals("gba") ? 0x22000000 : 0x22FFFFFF, 12);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(gradient(th.equals("midnight") ? 0xFF2DE2C8 : accent, th.equals("midnight") ? 0xFF2A9BFF : accent, 12, GradientDrawable.Orientation.LEFT_RIGHT), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new Drawable[]{track, fill}); ld.setId(0, android.R.id.background); ld.setId(1, android.R.id.progress);
        playProgress.setProgressDrawable(ld);
        center.addView(playProgress, new LinearLayout.LayoutParams(px(760), px(22)));
        downloadLabel = tv("", 26, muted, fBody); downloadLabel.setPadding(0, px(14), 0, px(8)); center.addView(downloadLabel);
        stepsRow = horizontal(); stepsRow.setPadding(0, px(8), 0, px(20)); center.addView(stepsRow);
        TextView cancel = tv("B  " + T.t("Cancelar descarga"), 28, text, fHead); cancel.setPadding(px(34), px(14), px(34), px(14));
        focusableWith(cancel, "cancel_load", f -> { GradientDrawable g = round(th.equals("midnight") ? 0x14FFFFFF : 0x22000000, 30); if (f) g.setStroke(px(4), th.equals("midnight") ? accent : text); return g; }, this::backToLibrary);
        center.addView(cancel);
        TextView note = tv(T.t("La ROM se elimina de esta TV al salir"), 22, muted, fBody); note.setPadding(0, px(14), 0, 0); center.addView(note);
        updateDownload();
    }
    private TextView loadingTitle; private LinearLayout stepsRow; private Game focusedGame; private long lastBytes, lastBytesAt; private double speed;
    private void setSteps(int active) {
        if (stepsRow == null) return; stepsRow.removeAllViews();
        String[] names = {T.t("Descargar de Telegram"), T.t("Restaurar partidas"), T.t("Iniciar núcleo"), T.t("Jugar")};
        for (int i = 0; i < names.length; i++) {
            TextView s = tv((i < active ? "✓ " : i == active ? "● " : "○ ") + names[i], 22, i <= active ? text : muted, fBody); s.setPadding(px(18), 0, px(18), 0); stepsRow.addView(s);
        }
    }
    private void updateDownload() {
        if (selected == null || selected.telegramAccount == 0 || downloadLabel == null) return;
        org.drinkless.tdlib.TdApi.File file = drive.file(selected);
        if (file != null && file.local.isDownloadingCompleted) {
            setSteps(1); playProgress.setProgress(1000); downloadLabel.setText(T.t("Listo"));
            if (pendingPlay.equals(selected.id) && page.equals("loading")) { pendingPlay = ""; playDownloaded(file.local.path); }
        } else if (file != null && file.local.isDownloadingActive) {
            long total = Math.max(1, Math.max(file.size, file.expectedSize)), done = file.local.downloadedSize;
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastBytesAt != 0 && now > lastBytesAt && done >= lastBytes) { double inst = (done - lastBytes) * 1000.0 / (now - lastBytesAt); speed = speed == 0 ? inst : speed * .7 + inst * .3; }
            lastBytes = done; lastBytesAt = now;
            setSteps(0); playProgress.setProgress((int) (done * 1000 / total));
            String a = android.text.format.Formatter.formatFileSize(this, done), b = android.text.format.Formatter.formatFileSize(this, total);
            downloadLabel.setText(a + T.t(" de ") + b + (speed > 0 ? " · " + android.text.format.Formatter.formatFileSize(this, (long) speed) + "/s" : "") + " · " + (int) (done * 100 / total) + "%");
        } else {
            setSteps(0); downloadLabel.setText(drive.error.isEmpty() ? T.t("Preparando tu juego…") : drive.error);
        }
    }
    private void playSelected() {
        if (selected == null) return;
        if (TemporaryRoms.busy()) { String id = selected.id; root.postDelayed(() -> { if (selected != null && selected.id.equals(id)) playSelected(); }, 120); return; }
        org.drinkless.tdlib.TdApi.File file = drive.file(selected);
        if (file != null && file.local.isDownloadingCompleted) { playDownloaded(file.local.path); return; }
        String cached = cachedRom(selected); if (cached != null) { playDownloaded(cached); return; }
        if (!drive.owns(selected)) { connectScreen(); return; }
        pendingPlay = selected.id; lastBytes = 0; lastBytesAt = 0; speed = 0; rememberLibrary(); page = "loading"; lastFocus = "cancel_load"; render();
        drive.download(selected); updateDownload();
    }
    private void refreshCatalog() {
        if(drive.stage==TirsoDrive.Stage.READY){
            boolean assigned=false;for(TirsoDrive.Source s:drive.sources.values())if(s.chatId!=0)assigned=true;
            if(!assigned){connectScreen();return;}
            if(console!=null&&!favorites)drive.refresh(console);else drive.sync();Toast.makeText(this,drive.syncing?T.t("Actualizando colección…"):drive.status,Toast.LENGTH_SHORT).show();
        }
        else connectScreen();
    }
    private void sortGames() {
        new AlertDialog.Builder(this).setTitle(T.t("Orden alfabético"))
            .setSingleChoiceItems(new String[]{T.t("Título: A → Z"),T.t("Título: Z → A")},library.descending()?1:0,(dialog,which)->{
                library.descending(which==1);libraryScroll=0;lastFocus="sort";dialog.dismiss();render();
            }).setNegativeButton(T.t("Cancelar"),null).show();
    }
    private String cachedRom(Game game){
        String path=getSharedPreferences("downloaded_roms",MODE_PRIVATE).getString(game.id,null);
        if(path==null)return null;
        try{java.io.File f=new java.io.File(path).getCanonicalFile();return f.isFile()&&f.getPath().startsWith(getFilesDir().getCanonicalPath()+java.io.File.separator)?f.getPath():null;}
        catch(java.io.IOException e){return null;}
    }
    private void playDownloaded(String path){
        pendingPlay="";library.addRecent(selected);page="library";lastFocus="game_"+selected.id;libraryFocus=lastFocus;render();
        Intent intent=new Intent(this,GameActivity.class).putExtra("rom",path).putExtra("filename",selected.fileName).putExtra("title",selected.title).putExtra("description",selected.description).putExtra("console",selected.console.name()).putExtra("cover",selected.coverName).putExtra("fileId",selected.fileId).putExtra("account",selected.telegramAccount);
        startActivity(intent);
    }
    private void search() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(query); input.setHint(T.t("Nombre del juego"));
        new AlertDialog.Builder(this).setTitle(T.t("Buscar juego")).setView(input)
                .setPositiveButton(T.t("Buscar"), (d,w) -> { query = input.getText().toString().trim();libraryScroll=0;libraryFocus="";lastFocus="search";if(!page.equals("library")){page="library";if(console==null&&!favorites){/* all consoles */}}render(); })
                .setNegativeButton(T.t("Cancelar"), null).show();
    }
    private void settings() {
        heading(T.t("Ajustes"), T.t("A tu manera")); content = scrollContent();content.setClipChildren(false);
        settingsSection(T.t("Apariencia"));LinearLayout appearance=horizontal();content.addView(appearance);
        settingTile(appearance,T.t("Diseño"),T.t(themeName()),"visual_theme",()->{
            String[] themes = {"Moderno", "GBA clásico", "Mosaico", "Consola completa"};
            String[] shown = new String[themes.length];for(int i=0;i<themes.length;i++)shown[i]=T.t(themes[i]);
            int current=java.util.Arrays.asList(themes).indexOf(themeName());
            new AlertDialog.Builder(this).setTitle(T.t("Tema visual")).setSingleChoiceItems(shown,current,(d,w)->{library.theme(themes[w]);d.dismiss();render();}).show();
        });
        settingTile(appearance,"Idioma / Language",T.english()?"English":"Español","language",()->{
            String[] codes={"es","en"};String[] names={"Español","English"};
            new AlertDialog.Builder(this).setTitle("Idioma / Language").setSingleChoiceItems(names,T.english()?1:0,(d,w)->{T.set(this,codes[w]);d.dismiss();recreate();}).show();
        });
        settingTile(appearance,T.t("Logotipos"),logoMode()?T.t("Logos de consola"):T.t("Texto"),"logo_mode",()->{getSharedPreferences("ui",MODE_PRIVATE).edit().putBoolean("logos",!logoMode()).apply();render();});
        LinearLayout section=horizontal();TextView sectionTitle=label(T.t("Consolas visibles"),19,text,true);section.addView(sectionTitle,new LinearLayout.LayoutParams(0,-2,1));
        section.addView(button(T.t("Mostrar todas"),"show_all",()->{for(Console c:Console.values())library.visible(c,true);render();}));content.addView(section);
        LinearLayout row=null;int n=0;
        for(Console c:Console.values()){
            if(n++%3==0){row=horizontal();row.setClipChildren(false);content.addView(row);}
            LinearLayout card=vertical();card.setPadding(dp(15),dp(12),dp(15),dp(12));card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            LinearLayout top=horizontal();ImageView image=logo(c);image.setPadding(dp(6),dp(3),dp(6),dp(3));image.setBackground(panel(0xFFF0F3FA,false));top.addView(image,new LinearLayout.LayoutParams(dp(75),dp(34)));
            top.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
            android.widget.Switch toggle=new android.widget.Switch(this);toggle.setChecked(library.visible(c));toggle.setClickable(false);toggle.setFocusable(false);toggle.setShowText(false);
            toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));toggle.setTrackTintList(android.content.res.ColorStateList.valueOf(library.visible(c)?accent:0xFF4A5568));top.addView(toggle);
            card.addView(top);TextView name=label(c.title,14,text,true);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
            card.addView(label(gameCount(library.count(c)),11,muted,false));card.setContentDescription(c.title+", "+(library.visible(c)?"visible":"oculta")+", "+gameCount(library.count(c)));
            focusable(card,"visible_"+c.name(),surface,()->{library.visible(c,!library.visible(c));lastFocus="visible_"+c.name();render();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(112),1);p.setMargins(dp(5),dp(5),dp(5),dp(5));row.addView(card,p);
        }
        settingsSection(T.t("Tu biblioteca"));LinearLayout connection=horizontal();content.addView(connection);
        settingTile(connection,"TirsoDrive",drive.stage==TirsoDrive.Stage.READY?T.t("Conectado · gestionar grupos"):T.t("Vincular colección"),"telegram",this::connectScreen);
        settingTile(connection,T.t("Mandos"),T.t("Perfiles independientes"),"controllers",this::controllers);
        LinearLayout system=horizontal();content.addView(system);
        settingTile(system,T.t("Archivos del sistema"),T.t("BIOS y archivos compartidos"),"system_files",this::importSystemFile);
        settingTile(system,"RetroStream",BuildConfig.VERSION_NAME,"about",()->message("RetroStream "+BuildConfig.VERSION_NAME,T.t("Nueve consolas integradas. Las ROMs se preparan al pulsar Jugar y se eliminan al salir. Tus partidas y estados permanecen en la TV.\n\nMAME: ROMs compatibles con MAME 2003-Plus; ciertos juegos requieren BIOS o archivos parent. Puedes añadirlos en Archivos del sistema.\n\nCódigo y licencias de los núcleos incluidos en la release y en los avisos de esta aplicación.")));
        LinearLayout gameRow=horizontal();content.addView(gameRow);
        String res=Presets.resolution(this);
        settingTile(gameRow,T.t("Resolución"),T.t("high".equals(res)?"Alta":"low".equals(res)?"Baja":"Media")+" · N64","resolution",()->{
            String cur=Presets.resolution(this);Presets.setResolution(this,"low".equals(cur)?"medium":"medium".equals(cur)?"high":"low");render();});
        LinearLayout updates=horizontal();content.addView(updates);
        settingTile(updates,T.t("Actualizaciones"),T.t("Buscar e instalar la última versión"),"updates",this::checkUpdates);
        settingTile(updates,T.t("Informe de problemas"),T.t("Qué registró Android si un juego falla"),"crash_report",this::crashReport);
        content.addView(button(T.t("Licencias y créditos"),"licenses",this::licenses));
    }
    private void crashReport(){
        Toast.makeText(this,T.t("Preparando informe…"),Toast.LENGTH_SHORT).show();
        new Thread(()->{
            String report=CrashReport.build(this);
            runOnUiThread(()->{
                String preview=report.length()>1800?report.substring(0,1800)+"\n…":report;
                new AlertDialog.Builder(this).setTitle(T.t("Informe de problemas")).setMessage(preview)
                    .setPositiveButton(T.t("Guardar en Descargas"),(d,w)->{String where=CrashReport.save(this,report);Toast.makeText(this,where==null?T.t("No se pudo guardar el informe"):T.t("Guardado en ")+where,Toast.LENGTH_LONG).show();})
                    .setNegativeButton(T.t("Cerrar"),null).show();
            });
        },"crash-report").start();
    }
    private void checkUpdates(){
        Toast.makeText(this,T.t("Buscando actualizaciones…"),Toast.LENGTH_SHORT).show();
        new Thread(()->{
            try{
                Updater.Info info=Updater.check();
                runOnUiThread(()->{
                    if(info.build<=BuildConfig.VERSION_CODE){message(T.t("Actualizaciones"),T.t("Ya tienes la última versión (build ")+BuildConfig.VERSION_CODE+").");return;}
                    new AlertDialog.Builder(this).setTitle(T.t("Nueva versión disponible"))
                        .setMessage("Build "+info.build+" · "+(info.size/(1024*1024))+" MB\n\n"+info.notes+T.t("\n\nTus partidas y la sesión de Telegram se conservan."))
                        .setPositiveButton(T.t("Actualizar ahora"),(d,w)->startUpdate(info)).setNegativeButton(T.t("Más tarde"),null).show();
                });
            }catch(Exception e){runOnUiThread(()->message(T.t("Actualizaciones"),T.t("No se pudo comprobar: ")+e.getMessage()));}
        },"update-check").start();
    }
    private void startUpdate(Updater.Info info){
        if(!getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(this).setTitle(T.t("Permiso necesario")).setMessage(T.t("Android pide permiso una sola vez para que RetroStream pueda instalar su propia actualización. Activa «Permitir de esta fuente» y vuelve a pulsar Actualizar."))
                .setPositiveButton(T.t("Abrir ajustes"),(d,w)->{try{startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:"+getPackageName())));}catch(Exception e){message(T.t("Permiso necesario"),T.t("Abre Ajustes de Android → Aplicaciones → RetroStream → Instalar apps desconocidas y permítelo."));}})
                .setNegativeButton(T.t("Cancelar"),null).show();
            return;
        }
        android.widget.ProgressBar bar=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);
        LinearLayout box=vertical();box.setPadding(dp(24),dp(16),dp(24),dp(8));box.addView(bar);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(T.t("Descargando actualización…")).setView(box).setCancelable(false).show();
        new Thread(()->{
            try{
                java.io.File apk=Updater.download(this,info,(done,total)->{int percent=(int)(done*100/Math.max(1,total));runOnUiThread(()->bar.setProgress(percent));});
                runOnUiThread(()->{dialog.dismiss();Toast.makeText(this,T.t("Instalando…"),Toast.LENGTH_LONG).show();});
                Updater.install(this,apk);
            }catch(Exception e){runOnUiThread(()->{dialog.dismiss();message(T.t("Actualizaciones"),e.getMessage());});}
        },"update-download").start();
    }
    private void settingsSection(String title){TextView view=label(title,19,text,true);view.setPadding(dp(5),dp(18),0,dp(8));content.addView(view);}
    private void settingTile(LinearLayout row,String title,String value,String tag,Runnable action){
        LinearLayout card=vertical();card.setPadding(dp(20),dp(14),dp(20),dp(14));card.addView(label(title,16,text,true));TextView subtitle=label(value,12,muted,false);subtitle.setSingleLine(true);subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(subtitle);
        focusable(card,tag,surface,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(84),1);p.setMargins(dp(5),dp(5),dp(5),dp(5));row.addView(card,p);
    }
    private void licenses(){try{String[] files=getAssets().list("licenses");new AlertDialog.Builder(this).setTitle(T.t("Licencias y créditos")).setItems(files,(d,w)->{
        try(InputStream in=getAssets().open("licenses/"+files[w])){message(files[w],new String(GameStorage.read(in,1024*1024),StandardCharsets.UTF_8));}catch(Exception e){message(T.t("Licencias"),T.t("No se pudo abrir este aviso"));}
    }).show();}catch(Exception e){message(T.t("Licencias"),T.t("Los avisos están disponibles en el código fuente de esta release"));}}
    private void importSystemFile(){
        new AlertDialog.Builder(this).setTitle(T.t("Archivos del sistema")).setMessage(T.t("Añade disksys.rom para Famicom Disk System o archivos ZIP de BIOS/parent para MAME. Conserva sus nombres originales. Usa archivos de los que dispongas legítimamente."))
            .setPositiveButton(T.t("Elegir archivo"),(d,w)->{try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),31);}catch(android.content.ActivityNotFoundException e){message(T.t("Archivos del sistema"),T.t("Esta TV necesita un selector de documentos compatible para importar archivos."));}}).setNegativeButton(T.t("Cancelar"),null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=31||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();new Thread(()->{try{
            String filename=null;try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0);}
            if(filename==null||(!filename.equalsIgnoreCase("disksys.rom")&&!filename.toLowerCase(java.util.Locale.ROOT).matches("[a-z0-9_+.\\-]+\\.zip")))throw new java.io.IOException(T.t("Selecciona disksys.rom o un ZIP con su nombre original"));
            java.io.File folder=new java.io.File(getFilesDir(),"bios");if(!folder.isDirectory()&&!folder.mkdirs())throw new java.io.IOException(T.t("No se pudo crear la carpeta del sistema"));
            java.io.File temp=java.io.File.createTempFile("system-",".tmp",getCacheDir());try{
                try(InputStream in=getContentResolver().openInputStream(uri);java.io.OutputStream out=new java.io.FileOutputStream(temp)){if(in==null)throw new java.io.IOException(T.t("No se pudo abrir el archivo"));GameStorage.copy(in,out,64L*1024*1024);}
                GameStorage.copyAtomic(temp,new java.io.File(folder,filename.toLowerCase(java.util.Locale.ROOT)));
            }finally{temp.delete();}runOnUiThread(()->Toast.makeText(this,T.t("Archivo del sistema añadido"),Toast.LENGTH_LONG).show());
        }catch(Exception e){runOnUiThread(()->message(T.t("Archivos del sistema"),e.getMessage()));}},"system-import").start();
    }
    private void controllers() {
        java.util.ArrayList<InputDevice> devices=new java.util.ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) devices.add(device);
        }
        if(devices.isEmpty()){message(T.t("Perfiles de controles"),T.t("No se detectaron mandos. Vincula uno por Bluetooth o USB desde los ajustes de Android TV."));return;}
        String[] names=new String[devices.size()];
        for(int i=0;i<names.length;i++)names[i]=ControllerProfiles.model(devices.get(i))+" · "+devices.get(i).getName();
        new AlertDialog.Builder(this).setTitle(T.t("Perfil por control")).setItems(names,(d,w)->controllerProfile(devices.get(w))).show();
    }
    private void controllerProfile(InputDevice device) {
        float[] values={.20f,.40f,.60f};String[] choices={T.t("Zona muerta: baja (20%)"),T.t("Zona muerta: media (40%)"),T.t("Zona muerta: alta (60%)")};
        int choice=0;float current=controllers.deadzone(device);for(int i=0;i<values.length;i++)if(Math.abs(values[i]-current)<.05f)choice=i;
        new AlertDialog.Builder(this).setTitle(ControllerProfiles.model(device))
            .setSingleChoiceItems(choices,choice,(dialog,which)->{
                controllers.deadzone(device,values[which]);Toast.makeText(this,T.t("Perfil guardado para este dispositivo"),Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(T.t("Diagnóstico"),(d,w)->{
                ControllerProfiles.State s=controllers.state(device.getId());
                message(T.t("Entradas independientes"),String.format(java.util.Locale.ROOT,
                    T.t("ID: %04x:%04x\nD-pad teclas: %d, %d\nD-pad HAT: %d, %d\nStick izquierdo: %.2f, %.2f\nStick derecho: %.2f, %.2f\n\nCada origen mantiene su estado separado. Los núcleos usan perfiles independientes por control y por jugador, sin mezclar D-pad y joystick."),
                    device.getVendorId(),device.getProductId(),s.keyX,s.keyY,s.hatX,s.hatY,s.leftX,s.leftY,s.rightX,s.rightY));
            }).setNegativeButton(T.t("Jugador"),(d,w)->new AlertDialog.Builder(this).setTitle(T.t("Asignar mando"))
                .setSingleChoiceItems(new String[]{T.t("Automático"),T.t("Jugador 1"),T.t("Jugador 2"),T.t("Jugador 3"),T.t("Jugador 4")},controllers.player(device)+1,(x,which)->{controllers.player(device,which-1);x.dismiss();}).show()).setPositiveButton(T.t("Listo"),null).show();
    }
    private void message(String title, String body) { new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(T.t("Entendido"), null).show(); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(controllers.key(event,true))return true;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) { onBackPressed(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            View focus = getCurrentFocus(); if (focus != null && event.getRepeatCount() == 0) focus.performClick(); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START) { route("settings"); return true; }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && (page.equals("library")||page.equals("home"))) { search(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X && page.equals("library")) { if (focusedGame != null) { library.toggleFavorite(focusedGame); libraryFocus = "game_" + focusedGame.id; lastFocus = libraryFocus; render(); } return true; }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X && !page.equals("welcome") && !page.equals("loading")) { openFavorites(); return true; }
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent event) {
        controllers.key(event,false);
        if(keyCode==KeyEvent.KEYCODE_BUTTON_A||keyCode==KeyEvent.KEYCODE_BUTTON_B||keyCode==KeyEvent.KEYCODE_BUTTON_START||keyCode==KeyEvent.KEYCODE_BUTTON_X||(keyCode==KeyEvent.KEYCODE_BUTTON_Y&&(page.equals("library")||page.equals("home"))))return true;
        return super.onKeyUp(keyCode,event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if(event.getAction()==MotionEvent.ACTION_MOVE && (event.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK) {
            int direction=controllers.motion(event);
            if(direction!=0) {
                View focused=getCurrentFocus();
                if(focused!=null&&"hero_console".equals(focused.getTag())&&(direction==View.FOCUS_LEFT||direction==View.FOCUS_RIGHT))moveHero(direction==View.FOCUS_RIGHT?1:-1);
                else if(focused!=null){View next=focused.focusSearch(direction);if(next!=null)next.requestFocus();}
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
    @Override public void onBackPressed() {
        if(page.equals("welcome")){drive.skip();route("home");return;}
        if (page.equals("loading")) backToLibrary();
        else if (!page.equals("home")) route("home");
        else new AlertDialog.Builder(this).setTitle(T.t("Salir de RetroStream"))
                    .setPositiveButton(T.t("Salir"), (d,w) -> finish()).setNegativeButton(T.t("Cancelar"), null).show();
    }
    @Override protected void onDestroy() {drive.detach(driveListener);inputManager.unregisterInputDeviceListener(inputListener);artwork.close(); super.onDestroy(); }
    @Override protected void onPause(){cancelPending();super.onPause();}
}
