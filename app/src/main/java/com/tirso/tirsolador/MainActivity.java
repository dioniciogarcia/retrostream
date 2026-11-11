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
    private String embedded = null;
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
        if (BuildConfig.PHONE && PhoneGames.scanOn(this) && !PhoneGames.folders(this).isEmpty() && PhoneGames.canReadFolders()) scanFolders(false);
        if (getIntent() != null && getIntent().hasExtra("play_game")) getWindow().getDecorView().post(this::playFromShortcut);
        drive=TirsoDrive.get(this);
        inputManager=(android.hardware.input.InputManager)getSystemService(INPUT_SERVICE);
        inputListener=new android.hardware.input.InputManager.InputDeviceListener(){
            public void onInputDeviceAdded(int id) { }
            public void onInputDeviceChanged(int id) { controllers.disconnect(id); }
            public void onInputDeviceRemoved(int id) { controllers.disconnect(id); }
        };
        inputManager.registerInputDeviceListener(inputListener,null);
        // Phone app: opened from PhoneActivity for one settings-type page; going back from it closes it.
        if (getIntent() != null && getIntent().hasExtra("embed_page")) { embedded = getIntent().getStringExtra("embed_page"); page = embedded; }
        if (state != null) {
            page = state.getString("page", "home"); query = state.getString("query", "");
            favorites = state.getBoolean("favorites"); lastFocus = state.getString("focus", "");
            libraryFocus=state.getString("libraryFocus","");libraryScroll=state.getInt("libraryScroll",0);
            try { console = Console.valueOf(state.getString("console", "")); } catch (IllegalArgumentException ignored) { }
            for (Game g : library.games) if (g.id.equals(state.getString("game"))) selected = g;
        }
        if(!drive.onboarded()&&!drive.skipped()&&embedded==null)page="welcome";
        render();
        scrape();
        driveListener=catalog->{
            if(catalog){rememberLibrary();library=new Library(this);scrape();}
            if(!drive.skipped()&&(drive.stage==TirsoDrive.Stage.QR||drive.stage==TirsoDrive.Stage.PHONE||drive.stage==TirsoDrive.Stage.PASSWORD||drive.stage==TirsoDrive.Stage.CODE))page="welcome";
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
    private boolean dark;
    private int ink, tilesRing;
    private int ov(int c) { return (c & 0xFF000000) | (dark ? 0xFFFFFF : 0x000000); }
    private int ring() { return th.equals("midnight") ? (dark ? Color.WHITE : 0xFF142238) : th.equals("tiles") ? tilesRing : accent; }
    private int focusRing() { return th.equals("midnight") ? accent : th.equals("tiles") ? tilesRing : ink; }
    private int logoTint() { return th.equals("tiles") ? Color.WHITE : th.equals("midnight") ? (dark ? Color.WHITE : Color.BLACK) : ink; }
    private void applyTheme() {
        th = themeKey(); mono = !th.equals("midnight"); dark = library.dark(th);
        switch (th) {
            case "gba":
                if (dark) { background = 0xFF14142A; surface = 0xFF222240; text = 0xFFF1EFE9; muted = 0xFFA6A6C0; accent = 0xFF9D7BFF; ink = 0xFFF1EFE9; }
                else { ink = 0xFF1B1745; background = 0xFFF1EFE9; surface = Color.WHITE; text = ink; muted = 0xFF5A5A70; accent = 0xFF7A4DFF; }
                strokeColor = ink; break;
            case "tiles":
                if (dark) { background = 0xFF1C2447; surface = 0xFF2A3560; text = Color.WHITE; muted = 0xFFC8D1F5; accent = Color.WHITE; tilesRing = Color.WHITE; strokeColor = 0x33FFFFFF; }
                else { background = 0xFFEEF1FA; surface = Color.WHITE; text = 0xFF1C2447; muted = 0xFF4A5680; accent = 0xFF1C2447; tilesRing = 0xFF1C2447; strokeColor = 0x331C2447; }
                ink = text; break;
            case "console":
                if (dark) { background = 0xFF16161D; surface = 0xFF24242E; text = 0xFFF4F1EC; muted = 0xFFA0A0B0; accent = 0xFFFF5A4D; ink = 0xFFF4F1EC; }
                else { ink = 0xFF16161D; background = 0xFFF4F1EC; surface = Color.WHITE; text = ink; muted = 0xFF6B6B7A; accent = 0xFFE5382B; }
                strokeColor = ink; break;
            default:
                if (dark) { background = 0xFF07101F; surface = ov(0x12FFFFFF); text = 0xFFF4F7FC; muted = 0xFF8FA3BF; accent = 0xFF2DE2C8; strokeColor = ov(0x1FFFFFFF); }
                else { background = 0xFFF3F6FC; surface = Color.WHITE; text = 0xFF142238; muted = 0xFF52657A; accent = 0xFF007E89; strokeColor = 0x22142238; }
                ink = text;
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
        g.setColor(th.equals("midnight") ? ov(0x14FFFFFF) : th.equals("tiles") ? ov(0x22FFFFFF) : 0x00000000);
        if (f) g.setStroke(px(4), th.equals("midnight") ? accent : th.equals("tiles") ? Color.WHITE : strokeColor);
        else if (th.equals("midnight")) g.setStroke(px(1.5f), ov(0x24FFFFFF));
        return g;
    }
    private View icon(String name, String label, String key, Runnable click) {
        IconView v = new IconView(this, name, label, text); focusableWith(v, key, this::iconBg, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(60), px(60)); p.setMargins(px(7), 0, px(7), 0);
        v.setLayoutParams(p); return v;
    }
    private View friendsIcon() {
        View v = icon("users", T.t("Amigos"), "nav_friends", () -> route("friends"));
        if (drive.friendsChat() != 0) ((IconView) v).badge(drive.friendsOnline());
        return v;
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
            case SNES: return "Snes9x"; case NES: return "FCEUmm"; case MD: return "Genesis Plus GX";
            case PSP: return "PPSSPP"; case PS2: return "ARMSX2"; case GC: case WII: return "Dolphin"; default: return "MAME 2003-Plus";
        }
    }
    private String heroSubtitle(Console c) {
        switch (c) {
            case N64: return "NINTENDO 64"; case GBA: return "GAME BOY ADVANCE"; case GBC: return "GAME BOY COLOR"; case GB: return "GAME BOY";
            case NDS: return "NINTENDO DS"; case SNES: return "SUPER NINTENDO"; case NES: return "ENTERTAINMENT SYSTEM"; case MD: return "MEGA DRIVE";
            case PSP: return "PLAYSTATION PORTABLE"; case PS2: return "PLAYSTATION 2"; case GC: return "NINTENDO GAMECUBE"; case WII: return "NINTENDO WII";
            default: return T.english() ? "ARCADE" : "ARCADE";
        }
    }
    /** "color" (original colored logos), "mono" (white/black with outline) or "text". */
    private String logoStyle() { android.content.SharedPreferences p = getSharedPreferences("ui", MODE_PRIVATE); String v = p.getString("logo_style", null); if (v != null) return v; return p.getBoolean("logos", true) ? "color" : "text"; }
    private boolean logoMode() { return !logoStyle().equals("text"); }
    /** Puts the console logo in the view: original colors, or a monochrome one (white with a black outline, or tinted). */
    private void setLogo(ImageView iv, Console c, int tint) {
        String pkg = getPackageName(); int id;
        if (logoStyle().equals("color") && (id = getResources().getIdentifier("logoc_" + c.logo, "drawable", pkg)) != 0) { iv.setImageResource(id); iv.clearColorFilter(); return; }
        if (tint == Color.WHITE && (id = getResources().getIdentifier("logom_" + c.logo, "drawable", pkg)) != 0) { iv.setImageResource(id); iv.clearColorFilter(); return; }
        id = getResources().getIdentifier("logo_" + c.logo, "drawable", pkg); if (id != 0) iv.setImageResource(id);
        iv.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    private TextView pill(String s, int size, int color, int bg) {
        TextView t = tv(s, size, color, fBody); t.setPadding(px(22), px(9), px(22), px(9)); t.setBackground(round(bg, 40)); return t;
    }
    private View keyChip(String key, String name) {
        LinearLayout row = horizontal();
        if (mono) {
            row.addView(tv("● ", 22, text, fMono)); row.addView(tv((name).toUpperCase(java.util.Locale.ROOT), 22, text, fMono));
            ((TextView) row.getChildAt(1)).setLetterSpacing(.12f);
        } else {
            TextView k = tv(key, 22, text, fHead); k.setGravity(Gravity.CENTER); k.setPadding(px(18), px(6), px(18), px(6)); k.setBackground(round(ov(0x1FFFFFFF), 12));
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
            View st = new View(this);
            st.setContentDescription(ok ? T.t("TirsoDrive · conectado") : T.t("TirsoDrive · sin conectar"));
            final int dotColor = ok ? 0xFF3DDC84 : 0xFFFFB84D;
            focusableWith(st, "nav_drive", f -> { GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(dotColor); if (f) g.setStroke(px(5), Color.WHITE); return g; }, this::connectScreen);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(30), px(30)); sl.rightMargin = px(26); h.addView(st, sl);
            if (!welcome) {
                if (page.equals("library")) { h.addView(icon("sort", T.t("Ordenar juegos: ") + (library.descending() ? "Z a A" : "A a Z"), "sort", this::sortGames)); h.addView(icon("refresh", T.t("Refrescar catálogo"), "refresh", this::refreshCatalog)); }
                h.addView(icon("search", T.t("Buscar juegos"), "search", this::search));
                h.addView(icon("star", T.t("Favoritos"), "nav_favorites", this::openFavorites));
                h.addView(friendsIcon());
                h.addView(icon("history", T.t("Historial de juego"), "nav_history", () -> route("history")));
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
                right.addView(friendsIcon());
                right.addView(icon("history", T.t("Historial de juego"), "nav_history", () -> route("history")));
                right.addView(icon("settings", T.t("Ajustes"), "nav_settings", () -> route("settings")));
            }
            h.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return h;
    }
    private void openFavorites() { console = null; favorites = true; mode = 0; query = ""; libraryFocus = ""; libraryScroll = 0; route("library"); }

    private void scrape() {
        GameMeta.refresh(this, library.games, () -> {
            if (isFinishing() || isDestroyed()) return;
            rememberLibrary(); library = new Library(this);
            if (page.equals("home") || page.equals("library")) render();
        });
    }
    private void rememberLibrary() {
        if(page.equals("library")) {
            libraryScroll=activeScroll==null?0:activeScroll.getScrollY();
            if(lastFocus.startsWith("game_"))libraryFocus=lastFocus;
        }
    }
    private void cancelPending(){discFor="";discParts=null;if(!pendingPlay.isEmpty()&&selected!=null&&pendingPlay.equals(selected.id)){org.drinkless.tdlib.TdApi.File f=drive.file(selected);if(f!=null&&f.local.isDownloadingActive)drive.cancelDownload(selected);}pendingPlay="";}
    private void route(String target) { if (embedded != null && target.equals("home")) { finish(); return; } cancelPending();rememberLibrary();page = target; lastFocus = ""; heroEntry = true; render(); }
    private void backToLibrary() {cancelPending();page="library";lastFocus=libraryFocus;heroEntry=true;render();}
    private void render() {
        if (BuildConfig.PHONE) { int want = PhoneGames.lockLandscape(this) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER; if (getRequestedOrientation() != want) setRequestedOrientation(want); }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        // TV: the 1920x1080 design scaled to the screen. Phone: fit both sides (wide phones are taller than 16:9 allows),
        // and upright the same design is shown narrower.
        u = !BuildConfig.PHONE ? dm.widthPixels / 1920f
            : dm.widthPixels >= dm.heightPixels ? Math.min(dm.widthPixels / 1920f, dm.heightPixels / 1080f) : dm.widthPixels / 1180f;
        applyTheme();
        root = vertical();
        int sidePad = px(mono ? 80 : 70);
        root.setPadding(sidePad, px(mono ? 38 : 34), sidePad, px(mono ? 30 : 28));
        if (th.equals("midnight") && dark) root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0C2442, 0xFF07101F, 0xFF07101F, 0xFF0A2A33}));
        else root.setBackgroundColor(background);
        setContentView(root);
        content = null; activeScroll = null; catalogStatus = null; infoPanel = null;
        String monoTitle, monoRight;
        int systems = 0; for (Console c : Console.all()) if (library.visible(c)) systems++;
        switch (page) {
            case "library": monoTitle = favorites ? T.t("FAVORITOS") : console == null ? T.t("BIBLIOTECA") : console.title.toUpperCase(java.util.Locale.ROOT);
                monoRight = library.filtered(console, favorites || mode == 1, query).size() + T.t(" JUEGOS"); break;
            case "settings": monoTitle = T.t("AJUSTES"); monoRight = ""; break;
            case "backups": monoTitle = T.t("COPIAS"); monoRight = ""; break;
            case "history": monoTitle = T.t("HISTORIAL"); monoRight = ""; break;
            case "friends": monoTitle = T.t("AMIGOS"); monoRight = drive.friendsChat() != 0 ? drive.friendsOnline() + T.t(" EN LÍNEA") : ""; break;
            case "artwork": monoTitle = T.t("IMÁGENES"); monoRight = ""; break;
            case "resolution": monoTitle = T.t("RESOLUCIÓN"); monoRight = ""; break;
            case "phone": monoTitle = T.t("TELÉFONO"); monoRight = ""; break;
            case "consoles": monoTitle = T.t("CONSOLAS"); monoRight = ""; break;
            case "welcome": monoTitle = "TIRSODRIVE"; monoRight = ""; break;
            case "loading": monoTitle = T.t("CARGANDO"); monoRight = ""; break;
            case "home_layout": monoTitle = T.t("PANTALLA DE INICIO"); monoRight = ""; break;
            default:
                if (!BuildConfig.PHONE && HomeLayout.tvStyle(this).equals("a")) { monoTitle = T.t("INICIO"); monoRight = ""; break; }
                monoTitle = T.t("CONSOLAS");
                monoRight = th.equals("console") ? (heroIndex() + 1) + T.t(" DE ") + systems : systems + T.t(" SISTEMAS");
        }
        if (!page.equals("loading")) root.addView(buildHeader(monoTitle, monoRight));
        body = vertical(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page.equals("welcome")) welcome();
        else if (page.equals("settings")) settings();
        else if (page.equals("backups")) backupsScreen();
        else if (page.equals("history")) historyScreen();
        else if (page.equals("friends")) friendsScreen();
        else if (page.equals("artwork")) artworkScreen();
        else if (page.equals("resolution")) resolutionScreen();
        else if (page.equals("phone")) phoneScreen();
        else if (page.equals("consoles")) consolesScreen();
        else if (page.equals("home_layout")) homeLayoutScreen();
        else if (page.equals("loading") && selected != null) loadingScreen();
        else if (page.equals("library")) libraryScreen();
        else home();
        if (page.equals("library")) footer(new String[][]{{"A", T.t("Jugar")}, {"B", T.t("Volver")}, {"Y", T.t("Buscar")}, {"X", T.t("Favorito")}}, T.t("Carátulas de libretro · guardadas en la TV"));
        else if (page.equals("settings") || page.equals("welcome") || page.equals("backups") || page.equals("artwork") || page.equals("resolution") || page.equals("friends") || page.equals("phone") || page.equals("consoles") || page.equals("home_layout")) footer(new String[][]{{"A", T.t("Elegir")}, {"B", T.t("Volver")}}, null);
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
            for(Console c:Console.all()){
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
        }else if(drive.stage==TirsoDrive.Stage.PHONE){
            instructions.addView(button(T.t("Introducir número de teléfono"),"auth_phone",this::phoneInput));
            instructions.addView(button(T.t("Usar código QR"),"auth_qr",drive::useQr));
            instructions.addView(label(T.t("Telegram enviará un código a tu app de Telegram. El QR solo sirve si tienes Telegram en otro teléfono."),12,muted,false));
        }else if(drive.stage==TirsoDrive.Stage.PASSWORD)instructions.addView(button(T.t("Introducir contraseña"),"auth_password",()->authInput(true)));
        else if(drive.stage==TirsoDrive.Stage.CODE)instructions.addView(button(T.t("Introducir código"),"auth_code",()->authInput(false)));
        else instructions.addView(button(T.t("Reintentar conexión"),"retry_connection",drive::retry));
        if(BuildConfig.PHONE&&drive.stage==TirsoDrive.Stage.QR)instructions.addView(button(T.t("Usar número de teléfono"),"auth_phone",this::phoneInput));
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
    private void phoneInput(){
        android.content.SharedPreferences ui=getSharedPreferences("ui",MODE_PRIVATE);
        LinearLayout box=horizontal();box.setPadding(dp(20),dp(8),dp(20),0);
        EditText code=new EditText(this);code.setSingleLine(true);code.setInputType(android.text.InputType.TYPE_CLASS_PHONE);code.setText(ui.getString("phone_cc","+1"));
        EditText number=new EditText(this);number.setSingleLine(true);number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);number.setHint("809 555 0123");
        box.addView(code,new LinearLayout.LayoutParams(dp(80),-2));box.addView(number,new LinearLayout.LayoutParams(0,-2,1));
        new AlertDialog.Builder(this).setTitle(T.t("Tu número de Telegram")).setMessage(T.t("Código de país y número, por ejemplo +1 y 809 555 0123."))
            .setView(box).setPositiveButton(T.t("Enviar código"),(d,w)->{
                String cc=code.getText().toString().replaceAll("[^0-9]","");String n=number.getText().toString().replaceAll("[^0-9]","");
                if(n.isEmpty())return;
                String full=number.getText().toString().trim().startsWith("+")?"+"+n:"+"+(cc.isEmpty()?"1":cc)+n;
                ui.edit().putString("phone_cc","+"+(cc.isEmpty()?"1":cc)).apply();drive.phoneNumber(full);
            }).setNegativeButton(T.t("Cancelar"),null).show();
        number.requestFocus();
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
            case SNES:return 0xFFA06BFF;case NES:return 0xFFFF4040;case MD:return 0xFF2AA8FF;
            case PSP:return 0xFF6B7280;case PS2:return 0xFF2F5BD3;case GC:return 0xFF7C5CFF;case WII:return 0xFF38BDF8;default:return 0xFF14C9AA;
        }
    }
    private int shade(int color,float f){return 0xFF000000|((int)(((color>>16)&255)*f)<<16)|((int)(((color>>8)&255)*f)<<8)|(int)((color&255)*f);}
    private int blend(int a,int b,float t){
        int r=(int)(((a>>16)&255)*t+((b>>16)&255)*(1-t)),g=(int)(((a>>8)&255)*t+((b>>8)&255)*(1-t)),bl=(int)((a&255)*t+(b&255)*(1-t));
        return 0xFF000000|(r<<16)|(g<<8)|bl;
    }
    private void focusableWith(View v,String key,java.util.function.Function<Boolean,android.graphics.drawable.Drawable> bg,Runnable click){
        v.setTag(key);v.setFocusable(true);v.setClickable(true);v.setBackground(bg.apply(false));v.setOnClickListener(x->click.run());
        v.setOnFocusChangeListener((x,has)->{x.setBackground(bg.apply(has));if(has)lastFocus=key;});
    }
    private void heading(String title, String subtitle) {
        TextView t = tv(title, mono ? 58 : 60, text, fHead); t.setPadding(0, px(6), 0, 0); body.addView(t);
        TextView s = tv(subtitle, 26, muted, fBody); s.setPadding(0, px(10), 0, px(14)); body.addView(s);
    }
    private int onAccent() { return th.equals("midnight") ? 0xFF04121F : th.equals("gba") || th.equals("console") ? (dark ? 0xFF14142A : Color.WHITE) : 0xFF1C2447; }
    private TextView primary(String title, String key, Runnable click) {
        TextView t = tv(title, 30, onAccent(), fHead); t.setGravity(Gravity.CENTER); t.setPadding(px(40), px(20), px(40), px(20));
        focusableWith(t, key, focused -> {
            GradientDrawable g = th.equals("midnight") ? gradient(0xFF2DE2C8, 0xFF2A9BFF, 24, GradientDrawable.Orientation.LEFT_RIGHT) : round(th.equals("tiles") ? 0xFFFFC857 : ink, th.equals("tiles") ? 26 : 18);
            if (focused) g.setStroke(px(5), ring());
            return g;
        }, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, px(92)); p.setMargins(px(4), px(6), px(4), px(6)); t.setLayoutParams(p); return t;
    }
    private String wordmark(Console c) { return c.name(); }
    private int heroIndex() {
        java.util.ArrayList<Console> v = new java.util.ArrayList<>(); for (Console c : Console.all()) if (library.visible(c)) v.add(c);
        if (v.isEmpty()) return 0; int i = v.indexOf(heroConsole(v)); return Math.max(0, i);
    }
    private Drawable tileBg(Console c, boolean focused) {
        int brand = brand(c);
        if (th.equals("gba")) {
            GradientDrawable face = round(focused ? brand : surface, 28); face.setStroke(px(focused ? 5 : 3.5f), ink);
            if (!focused) return face;
            GradientDrawable shadow = round(ink, 28);
            android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new Drawable[]{shadow, face});
            l.setLayerInset(0, px(14), px(14), 0, 0); l.setLayerInset(1, 0, 0, px(14), px(14)); return l;
        }
        if (th.equals("tiles")) {
            GradientDrawable g = gradient(blend(0xFFFFFFFF, brand, .14f), shade(brand, .82f), 34, GradientDrawable.Orientation.TL_BR);
            if (focused) g.setStroke(px(7), tilesRing); return g;
        }
        int base = dark ? 0xFF0E1A2C : 0xFFFFFFFF;
        GradientDrawable g = gradient(blend(brand, base, focused ? .42f : .24f), base, 30, GradientDrawable.Orientation.TL_BR);
        g.setStroke(px(focused ? 4 : 1.5f), focused ? accent : ov(0x1FFFFFFF)); return g;
    }
    private View consoleTile(Console c) {
        LinearLayout tile = vertical(); tile.setGravity(Gravity.CENTER); tile.setPadding(px(20), px(16), px(20), px(24));
        boolean gba = th.equals("gba");
        View mark;
        if (logoMode()) {
            ImageView logo = logo(c); setLogo(logo, c, logoTint());
            mark = logo; tile.addView(mark, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            TextView wm = tv(wordmark(c), th.equals("midnight") ? 90 : th.equals("tiles") ? 124 : 92, gba ? ink : th.equals("midnight") ? text : Color.WHITE, fWord);
            wm.setGravity(Gravity.CENTER); wm.setSingleLine(true);
            if (th.equals("midnight") && dark) wm.setShadowLayer(px(30), 0, 0, brand(c));
            else if (th.equals("tiles")) wm.setShadowLayer(px(2), 0, px(7), 0x55000000);
            tile.addView(wm, new LinearLayout.LayoutParams(-1, 0, 1)); mark = wm;
            if (!gba) wm.setGravity(Gravity.CENTER);
        }
        TextView name = null;
        if (th.equals("midnight")) { name = tv(c.title, 27, text, fBody); name.setGravity(Gravity.CENTER); name.setSingleLine(true); tile.addView(name, new LinearLayout.LayoutParams(-1, -2)); }
        String countText = gameCount(library.count(c)); if (mono) countText = countText.toUpperCase(java.util.Locale.ROOT);
        TextView count = tv(countText, th.equals("tiles") ? 27 : 23, th.equals("tiles") ? 0xE6FFFFFF : muted, mono ? fMono : fBody);
        count.setGravity(Gravity.CENTER); if (mono) count.setLetterSpacing(.12f);
        if (th.equals("midnight")) { count.setPadding(px(24), px(8), px(24), px(8)); count.setBackground(round(dark ? 0x66000000 : 0x14000000, 40)); }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.gravity = Gravity.CENTER_HORIZONTAL; cp.topMargin = px(12); tile.addView(count, cp);
        tile.setContentDescription(c.title + ", " + library.count(c) + T.t(" juegos"));
        focusableWith(tile, "console_" + c.name(), focused -> tileBg(c, focused), () -> openConsole(c));
        if (gba) {
            View.OnFocusChangeListener base = tile.getOnFocusChangeListener();
            tile.setOnFocusChangeListener((v, has) -> {
                base.onFocusChange(v, has);
                if (mark instanceof TextView) ((TextView) mark).setTextColor(has ? Color.WHITE : ink);
                count.setTextColor(has ? 0xFFEDE6FF : muted); v.setScaleX(1f); v.setScaleY(1f);
            });
        }
        return tile;
    }
    private void home() {
        java.util.ArrayList<Console> visible = new java.util.ArrayList<>();
        for (Console c : Console.all()) if (library.visible(c)) visible.add(c);
        if (visible.isEmpty()) {
            content = scrollContent(); content.addView(label(T.t("No hay consolas visibles"), 20, text, true));
            content.addView(button(T.t("Elegir consolas"), "empty_settings", () -> route("settings"))); return;
        }
        if (!BuildConfig.PHONE && HomeLayout.tvStyle(this).equals("a")) { homeA(visible); return; }
        if (th.equals("console")) { heroHome(visible); return; }
        if (th.equals("midnight")) {
            TextView t = tv(T.t("Tu biblioteca retro"), 62, text, fHead); t.setPadding(0, px(18), 0, 0); body.addView(t);
            body.addView(spacer(), new LinearLayout.LayoutParams(-1, px(24)));
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
        java.util.ArrayList<Console> consoles = new java.util.ArrayList<>(); for (Console c : Console.all()) if (library.visible(c)) consoles.add(c);
        if (consoles.isEmpty()) return;
        int index = consoles.indexOf(heroConsole(consoles));
        heroDir = delta > 0 ? 1 : -1; library.homeConsole(consoles.get((index + delta + consoles.size()) % consoles.size())); lastFocus = "hero_console"; render();
    }
    // ---- Big Console: ambient + transition animations (no scale-on-focus)
    private ImageView heroLogo; private TextView heroWord; private int heroDir; private boolean heroEntry = true; private float heroPhase;
    private Drawable heroBg(Console c, boolean focused) {
        float p = heroPhase;
        GradientDrawable g = gradient(blend(0xFFFFFFFF, brand(c), .10f + .12f * p), shade(brand(c), .72f + .12f * (1f - p)), 58, GradientDrawable.Orientation.TL_BR);
        if (focused) g.setStroke(px(7), ink); return g;
    }
    private void bindCancel(View v, android.animation.Animator... anims) {
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View x) { }
            public void onViewDetachedFromWindow(View x) { for (android.animation.Animator a : anims) a.cancel(); }
        });
    }
    private void animateHero(LinearLayout hero, View logoOrWord, View sub, View info, View chipOn) {
        android.view.animation.Interpolator soft = new android.view.animation.AccelerateDecelerateInterpolator();
        // slow shifting gradient behind the logo
        android.animation.ValueAnimator bg = android.animation.ValueAnimator.ofFloat(0f, 1f);
        bg.setDuration(4600); bg.setRepeatCount(android.animation.ValueAnimator.INFINITE); bg.setRepeatMode(android.animation.ValueAnimator.REVERSE); bg.setInterpolator(soft);
        final Console shown = heroConsole(visibleConsoles());
        bg.addUpdateListener(a -> { heroPhase = (float) a.getAnimatedValue(); hero.setBackground(heroBg(shown, hero.isFocused())); });
        bg.start();
        // the logo floats and breathes
        android.animation.ObjectAnimator floatY = android.animation.ObjectAnimator.ofFloat(logoOrWord, View.TRANSLATION_Y, -px(11), px(11));
        floatY.setDuration(3200); floatY.setRepeatCount(android.animation.ValueAnimator.INFINITE); floatY.setRepeatMode(android.animation.ValueAnimator.REVERSE); floatY.setInterpolator(soft);
        android.animation.ObjectAnimator breatheX = android.animation.ObjectAnimator.ofFloat(logoOrWord, View.SCALE_X, 1f, 1.035f), breatheY = android.animation.ObjectAnimator.ofFloat(logoOrWord, View.SCALE_Y, 1f, 1.035f);
        for (android.animation.ObjectAnimator a : new android.animation.ObjectAnimator[]{breatheX, breatheY}) { a.setDuration(3200); a.setRepeatCount(android.animation.ValueAnimator.INFINITE); a.setRepeatMode(android.animation.ValueAnimator.REVERSE); a.setInterpolator(soft); }
        floatY.start(); breatheX.start(); breatheY.start();
        bindCancel(hero, bg, floatY, breatheX, breatheY);
        // entrance: slide in from the side we moved towards, label and count rise in after it
        if (heroEntry || heroDir != 0) {
            hero.setAlpha(0f); hero.setTranslationX(heroDir != 0 ? heroDir * px(150) : 0); hero.setTranslationY(heroDir != 0 ? 0 : px(50));
            hero.animate().alpha(1f).translationX(0).translationY(0).setDuration(380).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            View[] rise = {sub, info}; long delay = 140;
            for (View v : rise) { v.setAlpha(0f); v.setTranslationY(px(26)); v.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(340).setInterpolator(new android.view.animation.DecelerateInterpolator()).start(); delay += 90; }
            if (chipOn != null) { chipOn.setScaleX(.8f); chipOn.setScaleY(.8f); chipOn.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start(); }
        }
        heroEntry = false; heroDir = 0;
    }
    private java.util.List<Console> visibleConsoles() { java.util.ArrayList<Console> v = new java.util.ArrayList<>(); for (Console c : Console.all()) if (library.visible(c)) v.add(c); return v; }
    private void heroHome(java.util.List<Console> consoles) {
        Console current = heroConsole(consoles); heroLogo = null; heroWord = null;
        if (lastFocus.isEmpty()) lastFocus = "hero_console";
        content = vertical(); body.addView(content, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout hero = vertical(); hero.setGravity(Gravity.CENTER); hero.setPadding(px(40), px(30), px(40), px(30));
        if (logoMode()) { ImageView hl = logo(current); setLogo(hl, current, Color.WHITE); heroLogo = hl; hero.addView(hl, new LinearLayout.LayoutParams(px(900), px(360))); }
        else { TextView wm = tv(wordmark(current), 330, Color.WHITE, fWord); wm.setGravity(Gravity.CENTER); wm.setSingleLine(true); wm.setShadowLayer(px(2), 0, px(8), 0x33000000); hero.addView(wm, new LinearLayout.LayoutParams(-2, -2)); heroLogo = null; heroWord = wm; }
        TextView sub = tv(heroSubtitle(current), 46, brand(current), fMono); sub.setLetterSpacing(.06f); sub.setPadding(px(34), px(10), px(34), px(10)); sub.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.topMargin = px(10); hero.addView(sub, sp);
        LinearLayout info = horizontal(); info.setGravity(Gravity.CENTER_VERTICAL); info.setPadding(px(36), px(12), px(36), px(12));
        GradientDrawable pill = round(0x47000000, 60); info.setBackground(pill);
        TextView num = tv(String.valueOf(library.count(current)), 58, Color.WHITE, fHead); info.addView(num);
        TextView unit = tv(T.t("JUEGOS"), 26, 0xCCFFFFFF, fMono); unit.setLetterSpacing(.18f); unit.setPadding(px(16), px(14), 0, 0); info.addView(unit);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-2, -2); infoLp.topMargin = px(34); hero.addView(info, infoLp);
        hero.setContentDescription(current.title + ", " + library.count(current) + T.t(" juegos. Abrir biblioteca"));
        focusableWith(hero, "hero_console", f -> heroBg(current, f), () -> openConsole(current));
        View.OnFocusChangeListener heroBase = hero.getOnFocusChangeListener();
        hero.setOnFocusChangeListener((v, has) -> { heroBase.onFocusChange(v, has); v.setScaleX(1f); v.setScaleY(1f); v.setElevation(0); });
        hero.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { moveHero(-1); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { moveHero(1); return true; }
            return false;
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, 0, 1); hp.bottomMargin = px(24); content.addView(hero, hp);
        LinearLayout strip = horizontal(); strip.setGravity(Gravity.CENTER); View chipOn = null;
        for (Console c : consoles) {
            boolean on = c == current;
            TextView chip = tv(c.name(), 27, on ? Color.WHITE : muted, fMono); chip.setLetterSpacing(.1f); chip.setGravity(Gravity.CENTER);
            focusableWith(chip, "chip_" + c.name(), f -> { GradientDrawable g = round(on ? brand(c) : surface, 20); if (f) g.setStroke(px(5), ink); return g; }, () -> { heroDir = consoles.indexOf(c) >= consoles.indexOf(current) ? 1 : -1; library.homeConsole(c); lastFocus = "hero_console"; render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(120), px(78)); cp.setMargins(px(8), 0, px(8), 0); strip.addView(chip, cp); if (on) chipOn = chip;
        }
        content.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        animateHero(hero, heroLogo != null ? heroLogo : heroWord, sub, info, chipOn);
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
            focusableWith(chip, "mode_" + i, f -> { GradientDrawable g = round(on ? (focusRing()) : (th.equals("midnight") ? ov(0x14FFFFFF) : ov(0x22FFFFFF)), 40); if (f) g.setStroke(px(4), th.equals("midnight") ? Color.WHITE : accent); return g; },
                () -> { favorites = false; mode = m; libraryScroll = 0; libraryFocus = ""; lastFocus = "mode_" + m; render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, px(64)); cp.leftMargin = px(12); titleRow.addView(chip, cp);
        }
        left.addView(titleRow);
        catalogStatus = tv(catalogMessage(), 20, muted, fBody); catalogStatus.setPadding(0, 0, 0, px(10)); left.addView(catalogStatus);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        LinearLayout grid = vertical(); grid.setPadding(px(8), px(8), px(8), px(24)); scroll.addView(grid);
        left.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = grid;
        int columns = gba ? 3 : 4; int gap = px(gba ? 26 : 24);
        int panelW = px(gba ? 600 : 560);
        int gridW = getResources().getDisplayMetrics().widthPixels - 2 * px(mono ? 80 : 70) - panelW - px(40) - px(16);
        int cardW = (gridW - columns * (gap + px(14))) / columns - 2; int cardH = Math.round(cardW * (gba ? .66f : .72f));
        LinearLayout row = null; int index = 0;
        for (Game game : games) {
            if (index % columns == 0) { row = horizontal(); row.setGravity(Gravity.TOP); grid.addView(row); }
            LinearLayout cell = vertical();
            FrameLayout art = new FrameLayout(this);
            art.setBackground(round(Color.WHITE, 22));
            TextView ph = tv(game.title, 26, 0xFF1A1A2E, fHead); ph.setGravity(Gravity.CENTER); ph.setPadding(px(16), px(12), px(16), px(12)); ph.setMaxLines(3); ph.setEllipsize(android.text.TextUtils.TruncateAt.END);
            art.addView(ph, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription(game.title); image.setPadding(px(14), px(12), px(14), px(12));
            art.addView(image, new FrameLayout.LayoutParams(-1, -1)); artwork.loadFirst(game, image, "Named_Logos", "Named_Titles", "Named_Boxarts");
            if (library.favorite(game)) { TextView fav = tv("★", 30, 0xFFFFB400, fHead); fav.setPadding(px(10), px(4), px(12), 0); art.addView(fav, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END)); }
            cell.addView(art, new LinearLayout.LayoutParams(cardW, cardH));
            cell.setPadding(px(7), px(7), px(7), px(7));
            final int ringColor = focusRing();
            focusableWith(cell, "game_" + game.id, f -> { GradientDrawable g = round(0, 28); if (f) g.setStroke(px(6), ringColor); return g; }, () -> selectGame(game));
            View.OnFocusChangeListener base = cell.getOnFocusChangeListener();
            cell.setOnFocusChangeListener((v, has) -> { base.onFocusChange(v, has); if (has) showInfo(game); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(gap / 2, gap / 4, gap / 2, gap / 4); row.addView(cell, p); index++;
        }
        if (index == 0) grid.addView(tv(favorites || mode == 1 ? T.t("Guarda juegos con X para verlos aquí.") : mode == 2 ? T.t("Aún no has jugado a nada.") : T.t("No hay juegos que coincidan."), 30, muted, fBody));
        // right panel
        infoPanel = vertical(); infoPanel.setPadding(px(32), px(32), px(32), px(32));
        if (gba) { GradientDrawable g = round(surface, 26); g.setStroke(px(4), ink); infoPanel.setBackground(g); }
        else infoPanel.setBackground(round(th.equals("midnight") ? ov(0x0FFFFFFF) : ov(0x22FFFFFF), 34));
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
        TextView title = tv(game.title, 38, gba ? ink : text, fHead); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setPadding(0, px(22), 0, 0); infoPanel.addView(title);
        String size = game.fileSize > 0 ? android.text.format.Formatter.formatFileSize(this, game.fileSize) : "—";
        TextView meta = tv(game.console.title + " · " + size, 24, gba ? 0xFF5A5A70 : muted, fBody); meta.setPadding(0, px(8), 0, px(10)); infoPanel.addView(meta);
        TextView desc = tv(game.description.isEmpty() ? T.t("Sin descripción disponible") : game.description, 24, gba ? 0xFF3A3A50 : muted, fBody); desc.setMaxLines(4); desc.setEllipsize(android.text.TextUtils.TruncateAt.END); infoPanel.addView(desc, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout chips = horizontal(); chips.setPadding(0, px(10), 0, px(14));
        chips.addView(pill(T.t("Tamaño ") + size, 22, gba ? ink : text, gba ? ov(0x14FFFFFF) : ov(0x14FFFFFF)));
        TextView core = pill(T.t("Núcleo ") + coreName(game.console), 22, gba ? ink : text, gba ? ov(0x14FFFFFF) : ov(0x14FFFFFF));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.leftMargin = px(12); chips.addView(core, cp);
        infoPanel.addView(chips);
        LinearLayout actions = horizontal();
        TextView play = primary(T.t("▶  Jugar"), "panel_play", () -> startPlay(game));
        actions.addView(play, new LinearLayout.LayoutParams(0, px(92), 1));
        TextView infoBtn = tv("i", 40, gba ? ink : text, fHead); infoBtn.setGravity(Gravity.CENTER); infoBtn.setContentDescription(T.t("Más información"));
        focusableWith(infoBtn, "panel_info", f -> { GradientDrawable g = round(ov(0x14FFFFFF), 26); if (f) g.setStroke(px(5), focusRing()); return g; }, () -> showFullInfo(game));
        LinearLayout.LayoutParams ip2 = new LinearLayout.LayoutParams(px(92), px(92)); ip2.leftMargin = px(12); actions.addView(infoBtn, ip2);
        TextView star = tv(library.favorite(game) ? "★" : "☆", 40, gba ? ink : text, fHead); star.setGravity(Gravity.CENTER);
        focusableWith(star, "panel_fav", f -> { GradientDrawable g = round(gba ? ov(0x14FFFFFF) : ov(0x14FFFFFF), 26); if (f) g.setStroke(px(5), focusRing()); return g; }, () -> { library.toggleFavorite(game); libraryFocus = "game_" + game.id; lastFocus = "panel_fav"; render(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(92), px(92)); sp.leftMargin = px(12); actions.addView(star, sp);
        TextView more = tv("⋯", 40, gba ? ink : text, fHead); more.setGravity(Gravity.CENTER); more.setContentDescription(T.t("Más opciones"));
        focusableWith(more, "panel_more", f -> { GradientDrawable g = round(ov(0x14FFFFFF), 26); if (f) g.setStroke(px(5), focusRing()); return g; }, () -> gameMore(game));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(92), px(92)); mp.leftMargin = px(12); actions.addView(more, mp);
        infoPanel.addView(actions);
    }
    // ------------------------------------------------------------------ more options: screenshots, home screen shortcut
    private void gameMore(Game game) {
        java.util.List<String> items = new java.util.ArrayList<>(); items.add(T.t("Capturas de pantalla"));
        boolean pin = android.os.Build.VERSION.SDK_INT >= 26 && getSystemService(android.content.pm.ShortcutManager.class) != null
            && getSystemService(android.content.pm.ShortcutManager.class).isRequestPinShortcutSupported();
        if (pin) items.add(T.t("Añadir a la pantalla de inicio"));
        new AlertDialog.Builder(this).setTitle(game.title).setItems(items.toArray(new String[0]), (d, w) -> { if (w == 0) showShots(game); else pinShortcut(game); }).show();
    }
    private void pinShortcut(Game game) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
        android.graphics.drawable.Icon icon = null;
        try { java.io.File f = artwork.cachedCover(game); if (f != null) { android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath());
            if (b != null) { int s = Math.min(b.getWidth(), b.getHeight()); android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
                icon = android.graphics.drawable.Icon.createWithAdaptiveBitmap(android.graphics.Bitmap.createScaledBitmap(sq, 432, 432, true)); } } } catch (Exception ignored) { }
        if (icon == null) icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.app_icon);
        Intent open = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra("play_game", game.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "game_" + Integer.toHexString(game.id.hashCode()))
            .setShortLabel(game.title.length() > 24 ? game.title.substring(0, 24) : game.title).setLongLabel(game.title).setIcon(icon).setIntent(open).build();
        sm.requestPinShortcut(info, null);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); playFromShortcut(); }
    /** A home screen shortcut opens its game straight away. */
    private void playFromShortcut() {
        String id = getIntent() == null ? null : getIntent().getStringExtra("play_game");
        if (id == null) return;
        getIntent().removeExtra("play_game");
        for (Game g : library.games) if (g.id.equals(id)) { console = g.console; page = "library"; render(); startWhenReady(g, 0); return; }
        Toast.makeText(this, T.t("Ese juego ya no está en tu biblioteca."), Toast.LENGTH_LONG).show();
    }
    /** Telegram games wait (up to ~20 s) for the Telegram connection before starting. */
    private void startWhenReady(Game g, int tries) {
        if (g.telegramAccount == 0 || drive.ready() || tries > 40) { startPlay(g); return; }
        root.postDelayed(() -> startWhenReady(g, tries + 1), 500);
    }
    private void showShots(Game game) {
        LinearLayout grid = vertical(); grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView status = tv(T.t("Cargando capturas…"), 26, 0xFF8C93A8, fBody); grid.addView(status);
        ScrollView sv = new ScrollView(this); sv.addView(grid);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(T.t("Capturas · ") + game.title).setView(sv).setNegativeButton(T.t("Cerrar"), null).create();
        dialog.show();
        if (!drive.ready()) { status.setText(T.t("Conecta TirsoDrive para ver las capturas.")); return; }
        drive.listShots(game.id, (list, err) -> {
            if (!dialog.isShowing()) return;
            if (list == null || list.isEmpty()) { status.setText(err != null ? err : T.t("Aún no hay capturas de este juego. En el juego: menú > Captura de pantalla, o Select + R1.")); return; }
            status.setText(list.size() + T.t(" capturas"));
            LinearLayout row = null; int i = 0;
            for (TirsoDrive.Shot s : list) {
                if (i++ % 2 == 0) { row = horizontal(); grid.addView(row); }
                ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF1A1E2B);
                iv.setFocusable(true); iv.setClickable(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(110), 1); lp.setMargins(dp(4), dp(4), dp(4), dp(4)); row.addView(iv, lp);
                drive.downloadPicture(s.thumbId, (path, e) -> { if (path != null) iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path)); });
                iv.setOnClickListener(v -> drive.downloadPicture(s.fullId, (path, e) -> {
                    if (path == null) return; ImageView big = new ImageView(this); big.setAdjustViewBounds(true); big.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path));
                    new AlertDialog.Builder(this).setView(big).setPositiveButton(T.t("Cerrar"), null).show();
                }));
            }
            if (row != null && row.getChildCount() == 1) row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(110), 1));
        });
    }
    private void showFullInfo(Game game) {
        Toast.makeText(this, T.t("Cargando información…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Artwork art = new Artwork(getApplicationContext());
            android.graphics.Bitmap box = null; java.util.List<android.graphics.Bitmap> shots = new java.util.ArrayList<>();
            try { java.io.File f = art.cachedCover(game); if (f != null) box = android.graphics.BitmapFactory.decodeFile(f.getPath()); } catch (Exception ignored) { }
            try { android.graphics.Bitmap b1 = art.snapshot(game, "Named_Snaps"); if (b1 != null) shots.add(b1); android.graphics.Bitmap b2 = art.snapshot(game, "Named_Titles"); if (b2 != null) shots.add(b2); } catch (Exception ignored) { }
            art.close();
            final android.graphics.Bitmap boxFinal = box;
            runOnUiThread(() -> { if (!isFinishing()) GameInfo.INSTANCE.show(this, GameMeta.info(this, game), boxFinal, shots, () -> kotlin.Unit.INSTANCE); });
        }, "game-info").start();
    }
    private void selectGame(Game game) {
        libraryFocus = "game_" + game.id; showInfo(game);
        if (infoPanel != null) { View play = infoPanel.findViewWithTag("panel_play"); if (play != null) play.requestFocusFromTouch(); }
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
        GradientDrawable track = round(th.equals("gba") ? ov(0x22FFFFFF) : ov(0x22FFFFFF), 12);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(gradient(th.equals("midnight") ? 0xFF2DE2C8 : accent, th.equals("midnight") ? 0xFF2A9BFF : accent, 12, GradientDrawable.Orientation.LEFT_RIGHT), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new Drawable[]{track, fill}); ld.setId(0, android.R.id.background); ld.setId(1, android.R.id.progress);
        playProgress.setProgressDrawable(ld);
        center.addView(playProgress, new LinearLayout.LayoutParams(px(760), px(22)));
        downloadLabel = tv("", 26, muted, fBody); downloadLabel.setPadding(0, px(14), 0, px(8)); center.addView(downloadLabel);
        stepsRow = horizontal(); stepsRow.setPadding(0, px(8), 0, px(20)); center.addView(stepsRow);
        TextView cancel = tv("B  " + T.t("Cancelar descarga"), 28, text, fHead); cancel.setPadding(px(34), px(14), px(34), px(14));
        focusableWith(cancel, "cancel_load", f -> { GradientDrawable g = round(th.equals("midnight") ? ov(0x14FFFFFF) : ov(0x22FFFFFF), 30); if (f) g.setStroke(px(4), th.equals("midnight") ? accent : text); return g; }, this::backToLibrary);
        center.addView(cancel);
        TextView note = tv(T.t("La ROM se elimina de esta TV al salir"), 22, muted, fBody); note.setPadding(0, px(14), 0, 0); center.addView(note);
        for (int i = 0; i < center.getChildCount(); i++) {
            View k = center.getChildAt(i); ViewGroup.LayoutParams lp0 = k.getLayoutParams();
            LinearLayout.LayoutParams lp = lp0 instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) lp0 : new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.CENTER_HORIZONTAL; if (lp.width == -1) lp.width = -2; k.setLayoutParams(lp);
            if (k instanceof TextView) ((TextView) k).setGravity(Gravity.CENTER);
        }
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
        if (updateDisc()) return;
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
        if (selected.id.startsWith("local:")) { Toast.makeText(this, T.t("Ese archivo ya no está en tu teléfono. Busca juegos de nuevo en Ajustes."), Toast.LENGTH_LONG).show(); return; }
        if (!drive.owns(selected)) { connectScreen(); return; }
        if (PhoneGames.wifiOnly(this) && !PhoneGames.onWifi(this)) { Toast.makeText(this, T.t("Solo Wi-Fi está activado: conéctate a una red Wi-Fi para descargar este juego."), Toast.LENGTH_LONG).show(); return; }
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
        if(game.id.startsWith("local:")){String p=PhoneGames.path(this,game.id);return p!=null&&new java.io.File(p).isFile()?p:null;}
        String path=getSharedPreferences("downloaded_roms",MODE_PRIVATE).getString(game.id,null);
        if(path==null)return null;
        try{java.io.File f=new java.io.File(path).getCanonicalFile();return f.isFile()&&f.getPath().startsWith(getFilesDir().getCanonicalPath()+java.io.File.separator)?f.getPath():null;}
        catch(java.io.IOException e){return null;}
    }
    // BIN/CUE discs: the .cue is downloaded first, then the tracks it lists; the player gets all of them.
    private java.util.List<Object[]> discParts;   // {file name, file id}
    private String discFor="",discCue="",discJson=null;
    private void prepareDisc(String cuePath){
        java.util.ArrayList<String> names=new java.util.ArrayList<>();
        try{for(String line:new String(java.nio.file.Files.readAllBytes(new java.io.File(cuePath).toPath()),StandardCharsets.UTF_8).split("\\r?\\n")){
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("^\\s*FILE\\s+\"?(.+?)\"?\\s+\\S+\\s*$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
            if(m.find()){String n=new java.io.File(m.group(1).replace('\\','/')).getName();if(!names.contains(n))names.add(n);}
        }}catch(Exception e){Toast.makeText(this,T.t("No se pudo leer el archivo .cue"),Toast.LENGTH_LONG).show();return;}
        if(names.isEmpty()){Toast.makeText(this,T.t("El archivo .cue no lista ninguna pista"),Toast.LENGTH_LONG).show();return;}
        Game game=selected;discFor=game.id;discCue=cuePath;discJson=null;discParts=null;
        pendingPlay=game.id;page="loading";lastFocus="cancel_load";render();
        drive.findParts(game,names,(list,error)->{
            if(selected!=game||!discFor.equals(game.id))return;
            if(error!=null||list==null){pendingPlay="";discFor="";Toast.makeText(this,error==null?T.t("No se encontraron las pistas del disco"):error,Toast.LENGTH_LONG).show();backToLibrary();return;}
            discParts=new java.util.ArrayList<>();
            for(int i=0;i<list.size();i++){discParts.add(new Object[]{names.get(i),list.get(i).id});if(!list.get(i).local.isDownloadingCompleted)drive.downloadPart(list.get(i).id);}
            updateDownload();
        });
    }
    /** Progress of the disc's tracks; true while they are still coming. */
    private boolean updateDisc(){
        if(selected==null||!discFor.equals(selected.id)||discJson!=null)return false;
        if(discParts==null){if(downloadLabel!=null)downloadLabel.setText(T.t("Buscando las pistas del disco…"));return true;}   // still looking (don't start the .cue again)
        long total=0,done=0;boolean all=true;org.json.JSONArray arr=new org.json.JSONArray();
        for(Object[] p:discParts){org.drinkless.tdlib.TdApi.File f=drive.part((Integer)p[1]);if(f==null){all=false;continue;}
            long size=Math.max(1,Math.max(f.size,f.expectedSize));total+=size;done+=f.local.isDownloadingCompleted?size:f.local.downloadedSize;
            if(!f.local.isDownloadingCompleted||!new java.io.File(f.local.path).isFile())all=false;
            else{try{arr.put(new org.json.JSONObject().put("name",(String)p[0]).put("path",f.local.path));}catch(org.json.JSONException ignored){}}}
        if(all){discJson=arr.toString();if(pendingPlay.equals(selected.id)&&page.equals("loading"))playDownloaded(discCue);return true;}
        setSteps(0);playProgress.setProgress((int)(done*1000/Math.max(1,total)));
        downloadLabel.setText(T.t("Pistas del disco: ")+android.text.format.Formatter.formatFileSize(this,done)+T.t(" de ")+android.text.format.Formatter.formatFileSize(this,total)+" · "+(int)(done*100/Math.max(1,total))+"%");
        return true;
    }
    private void playDownloaded(String path){
        if(path.toLowerCase(java.util.Locale.ROOT).endsWith(".cue")&&!(discCue.equals(path)&&discJson!=null&&selected!=null&&discFor.equals(selected.id))){prepareDisc(path);return;}
        String parts=path.toLowerCase(java.util.Locale.ROOT).endsWith(".cue")?discJson:null;
        if(PhoneGames.keep(this)&&selected!=null&&selected.telegramAccount!=0){PhoneGames.remember(this,selected,path);
            if(parts!=null)try{org.json.JSONArray a=new org.json.JSONArray(parts);for(int i=0;i<a.length();i++){String tp=a.getJSONObject(i).getString("path");PhoneGames.remember(this,new Game(selected.id+"#"+i,selected.title,selected.console,"",""),tp);}}catch(Exception ignored){}}
        pendingPlay="";library.addRecent(selected);page="library";lastFocus="game_"+selected.id;libraryFocus=lastFocus;render();
        Intent intent=new Intent(this,Console.ownProcess(selected.console)?GameActivityN64.class:GameActivity.class).putExtra("rom",path).putExtra("filename",selected.fileName).putExtra("title",selected.title).putExtra("description",selected.description).putExtra("console",selected.console.name()).putExtra("cover",selected.coverName).putExtra("gameid",selected.id).putExtra("meta",GameMeta.info(this,selected).toString()).putExtra("fileId",selected.fileId).putExtra("account",selected.telegramAccount);
        if(parts!=null)intent.putExtra("parts",parts);
        drive.playing(selected);
        launchWithNewestSave(intent,selected);
    }
    /** Before a game starts: if Telegram has a newer save of it (from the TV or another phone), it is restored first. */
    private void launchWithNewestSave(Intent intent,Game game){
        String sha=SaveBackups.shaOf(this,game.id);
        if(sha==null||!drive.ready()){startActivity(intent);return;}
        final boolean[] state={false,false};   // started, restoring
        Runnable go=()->{if(state[0])return;state[0]=true;startActivity(intent);};
        root.postDelayed(()->{if(!state[1])go.run();},7000);
        Toast.makeText(this,T.t("Buscando tu partida más reciente…"),Toast.LENGTH_SHORT).show();
        SaveBackups.pullNewest(this,drive,game.console,sha,()->{if(state[0])return false;state[1]=true;return true;},
            msg->{if(msg!=null)Toast.makeText(this,msg,Toast.LENGTH_LONG).show();go.run();return kotlin.Unit.INSTANCE;});
    }
    private void search() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(query); input.setHint(T.t("Nombre del juego"));
        new AlertDialog.Builder(this).setTitle(T.t("Buscar juego")).setView(input)
                .setPositiveButton(T.t("Buscar"), (d,w) -> { query = input.getText().toString().trim();libraryScroll=0;libraryFocus="";lastFocus="search";if(!page.equals("library")){page="library";if(console==null&&!favorites){/* all consoles */}}render(); })
                .setNegativeButton(T.t("Cancelar"), null).show();
    }
    private LinearLayout sRow() { LinearLayout r = horizontal(); r.setClipChildren(false); r.setClipToPadding(false); content.addView(r, new LinearLayout.LayoutParams(-1, -2)); return r; }
    private void sSection(String title) {
        TextView t = tv(mono ? title.toUpperCase(java.util.Locale.ROOT) : title, mono ? 22 : 28, mono ? muted : text, mono ? fMono : fHead);
        if (mono) t.setLetterSpacing(.14f); t.setPadding(px(10), px(20), 0, px(6)); content.addView(t);
    }
    private Drawable cardBg(boolean f) {
        GradientDrawable g;
        if (th.equals("midnight")) { g = gradient(ov(0x1AFFFFFF), ov(0x0DFFFFFF), 28, GradientDrawable.Orientation.TL_BR); g.setStroke(px(f ? 4 : 1.5f), f ? accent : ov(0x1FFFFFFF)); }
        else if (th.equals("tiles")) { g = round(f ? ov(0x44FFFFFF) : ov(0x24FFFFFF), 30); if (f) g.setStroke(px(5), tilesRing); }
        else { g = round(f ? (dark ? 0xFF33335A : 0xFFEDE6FF) : surface, 24); g.setStroke(px(f ? 5 : 3.5f), f ? accent : ink); }
        return g;
    }
    private void sCard(LinearLayout row, String title, String value, String code, int tint, String tag, Runnable action) {
        LinearLayout card = horizontal(); card.setPadding(px(22), px(14), px(22), px(14));
        TextView badge = tv(code, 24, Color.WHITE, fMono); badge.setGravity(Gravity.CENTER); badge.setBackground(gradient(tint, shade(tint, .72f), 16, GradientDrawable.Orientation.TL_BR));
        card.addView(badge, new LinearLayout.LayoutParams(px(68), px(68)));
        LinearLayout col = vertical(); col.setPadding(px(22), 0, 0, 0);
        TextView t = tv(title, 28, text, fHead); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(t);
        TextView v = tv(value, 22, muted, fBody); v.setSingleLine(true); v.setEllipsize(android.text.TextUtils.TruncateAt.END); v.setPadding(0, px(4), 0, 0); col.addView(v);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        focusableWith(card, tag, this::cardBg, action);
        View.OnFocusChangeListener base = card.getOnFocusChangeListener();
        card.setOnFocusChangeListener((x, has) -> { base.onFocusChange(x, has); x.setScaleX(1f); x.setScaleY(1f); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, px(104), 1); p.setMargins(px(10), px(6), px(10), px(6)); row.addView(card, p);
    }
    private void sFiller(LinearLayout row, int n) { for (int i = 0; i < n; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, px(104), 1)); }
    private void settings() {
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setPadding(0, 0, 0, px(24)); content.setClipChildren(false); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!mono) { TextView h = tv(T.t("Ajustes"), 50, text, fHead); h.setPadding(px(10), px(4), 0, 0); content.addView(h); }
        sSection(T.t("Apariencia")); LinearLayout a = sRow();
        sCard(a, T.t("Diseño"), T.t(themeName()), "DSN", 0xFF7A4DFF, "visual_theme", () -> {
            String[] themes = {"Moderno", "GBA clásico", "Mosaico", "Consola completa"};
            String[] shown = new String[themes.length]; for (int i = 0; i < themes.length; i++) shown[i] = T.t(themes[i]);
            int current = java.util.Arrays.asList(themes).indexOf(themeName());
            new AlertDialog.Builder(this).setTitle(T.t("Tema visual")).setSingleChoiceItems(shown, current, (d, w) -> { library.theme(themes[w]); d.dismiss(); lastFocus = "visual_theme"; render(); }).show();
        });
        sCard(a, T.t("Color"), dark ? T.t("Oscuro") : T.t("Claro"), "CLR", 0xFF4DB36B, "color_mode", () -> { library.dark(th, !dark); lastFocus = "color_mode"; render(); });
        sCard(a, "Idioma / Language", T.english() ? "English" : "Español", "LNG", 0xFF2AA8FF, "language", () -> {
            String[] codes = {"es", "en"}; String[] names = {"Español", "English"};
            new AlertDialog.Builder(this).setTitle("Idioma / Language").setSingleChoiceItems(names, T.english() ? 1 : 0, (d, w) -> { T.set(this, codes[w]); d.dismiss(); recreate(); }).show();
        });
        LinearLayout a2 = sRow(); sFiller(a2, 0);
        sCard(a2, T.t("Logotipos"), logoStyle().equals("color") ? T.t("Logos a color") : logoStyle().equals("mono") ? T.t("Logos monocromo") : T.t("Texto"), "LOG", 0xFFFF9A2E, "logo_mode", () -> { String n = logoStyle().equals("color") ? "mono" : logoStyle().equals("mono") ? "text" : "color"; getSharedPreferences("ui", MODE_PRIVATE).edit().putString("logo_style", n).apply(); lastFocus = "logo_mode"; render(); });
        sFiller(a2, 2);
        sSection(T.t("Tu biblioteca")); LinearLayout b = sRow();
        sCard(b, "TirsoDrive", drive.stage == TirsoDrive.Stage.READY ? T.t("Conectado · gestionar grupos") : T.t("Vincular colección"), "TD", 0xFF14C9AA, "telegram", this::connectScreen);
        sCard(b, T.t("Copias de partidas"), T.t("Guardadas en tu canal de Telegram"), "BAK", 0xFF2DE2C8, "backups", () -> openBackups());
        if (BuildConfig.PHONE) sCard(b, T.t("Archivos BIOS"), Bios.ps2(this) != null ? T.t("BIOS de PS2 lista") : T.t("Añade tu BIOS de PS2"), "BIOS", 0xFF4DB36B, "system_files", this::biosFiles);
        else sCard(b, T.t("Archivos del sistema"), T.t("BIOS y archivos compartidos"), "SYS", 0xFF4DB36B, "system_files", this::importSystemFile);
        sSection(T.t("Juego")); LinearLayout c = sRow();
        String resSummary = null; boolean resMixed = false;
        for (Console cn : Console.all()) { if (!library.visible(cn) || cn == Console.MAME) continue; String v = Presets.resolution(this, cn); if (resSummary == null) resSummary = v; else if (!resSummary.equals(v)) resMixed = true; }
        String resShown = resMixed ? T.t("Personalizada") : T.t("high".equals(resSummary) ? "Alta" : "low".equals(resSummary) ? "Baja" : "Media");
        sCard(c, T.t("Resolución"), resShown + " · " + T.t("Por consola"), "RES", 0xFF2F6DF6, "resolution", () -> route("resolution"));
        sCard(c, T.t("Mandos"), T.t("Perfiles independientes"), "PAD", 0xFFFF4D73, "controllers", this::controllers);
        sCard(c, T.t("Descargar imágenes"), T.t("Logos y carátulas"), "IMG", 0xFFFF9A2E, "artwork_dl", () -> route("artwork"));
        if (BuildConfig.PHONE) { LinearLayout d = sRow();
            sCard(d, T.t("Teléfono y almacenamiento"), T.t("Carpetas, juegos guardados, Wi-Fi"), "TEL", 0xFF38BDF8, "phone_page", () -> route("phone"));
            sCard(d, T.t("Controlador de GPU"), PhoneGames.driverName(this).isEmpty() ? T.t("El del teléfono") : PhoneGames.driverName(this), "GPU", 0xFFA06BFF, "gpu_driver", this::gpuDriver);
            sFiller(d, 1); }
        int shownConsoles = 0; for (Console cn : Console.all()) if (library.visible(cn)) shownConsoles++;
        LinearLayout cv = sRow();
        sCard(cv, T.t("Consolas"), shownConsoles + T.t(" de ") + Console.all().length + T.t(" visibles"), "CON", 0xFF14C9AA, "consoles_page", () -> route("consoles"));
        sCard(cv, T.t("Pantalla de inicio"), T.t("Qué secciones se muestran"), "HOM", 0xFF7A4DFF, "home_layout", () -> route("home_layout"));
        sCard(cv, T.t("Limpiar grupos vacíos"), T.t("Borra grupos TD duplicados sin archivos"), "DEL", 0xFFFF4D73, "cleanup_groups", this::cleanupGroups);
        sSection(T.t("Aplicación")); LinearLayout d = sRow();
        sCard(d, T.t("Actualizaciones"), T.t("Buscar e instalar la última versión"), "UPD", 0xFF7A4DFF, "updates", this::checkUpdates);
        sCard(d, T.t("Informe de problemas"), T.t("Qué registró Android si un juego falla"), "LOG", 0xFFFF9A2E, "crash_report", this::crashReport);
        sCard(d, "RetroStream", BuildConfig.VERSION_NAME, "ABT", 0xFF2AA8FF, "about", () -> message("RetroStream " + BuildConfig.VERSION_NAME, T.t("Nueve consolas integradas. Las ROMs se preparan al pulsar Jugar y se eliminan al salir. Tus partidas y estados permanecen en la TV y se copian a tu canal de Telegram.\n\nMAME: ROMs compatibles con MAME 2003-Plus; ciertos juegos requieren BIOS o archivos parent. Puedes añadirlos en Archivos del sistema.\n\nCódigo y licencias de los núcleos incluidos en la release y en los avisos de esta aplicación.")));
        LinearLayout e = sRow();
        sCard(e, T.t("Licencias y créditos"), T.t("Motores, fuentes y bases de datos"), "LIC", 0xFF4DB36B, "licenses", this::licenses);
        sFiller(e, 2);
    }

    // ------------------------------------------------------------------ save backups (Telegram)
    private java.util.List<TirsoDrive.Backup> backupList;
    private String backupStatus = "";
    private TextView backupStatusView;
    private boolean backupBusy;
    private void openBackups() { backupList = null; backupStatus = ""; route("backups"); loadBackups(); }
    private void setBackupStatus(String s) { backupStatus = s; if (backupStatusView != null) backupStatusView.setText(s); }
    private void loadBackups() {
        setBackupStatus(T.t("Leyendo tu canal de Telegram…"));
        SaveBackups.fetch(drive, null, (list, err) -> {
            if (list != null) { backupList = list; backupStatus = list.isEmpty() ? T.t("Aún no hay copias. Se crean solas al salir de un juego.") : ""; }
            else backupStatus = err == null ? "" : err;
            if (page.equals("backups")) { lastFocus = "bk_reload"; render(); }
            return kotlin.Unit.INSTANCE;
        });
    }
    private TextView actionButton(String title, String tag, boolean primary, Runnable click) {
        TextView t = tv(title, 28, primary ? onAccent() : text, fHead); t.setGravity(Gravity.CENTER); t.setPadding(px(34), px(16), px(34), px(16));
        focusableWith(t, tag, f -> {
            GradientDrawable g = primary ? (th.equals("midnight") ? gradient(0xFF2DE2C8, 0xFF2A9BFF, 26, GradientDrawable.Orientation.LEFT_RIGHT) : round(th.equals("tiles") ? 0xFFFFC857 : ink, 26))
                : round(th.equals("midnight") ? ov(0x18FFFFFF) : th.equals("tiles") ? ov(0x24FFFFFF) : surface, 26);
            if (!primary && !th.equals("midnight") && !th.equals("tiles")) g.setStroke(px(3.5f), ink);
            if (f) g.setStroke(px(5), ring()); return g; }, click);
        View.OnFocusChangeListener base = t.getOnFocusChangeListener(); t.setOnFocusChangeListener((x, has) -> { base.onFocusChange(x, has); x.setScaleX(1f); x.setScaleY(1f); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, px(88)); p.setMargins(px(8), px(6), px(8), px(6)); t.setLayoutParams(p); return t;
    }
    private void backupsScreen() {
        LinearLayout col = vertical(); body.addView(col, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!mono) { TextView h = tv(T.t("Copias de partidas"), 62, text, fHead); h.setPadding(px(10), px(10), 0, 0); col.addView(h); }
        TextView sub = tv(T.t("Canal de Telegram: ") + TirsoDrive.BACKUP_TITLE, 26, muted, fBody); sub.setPadding(px(10), px(8), 0, px(16)); col.addView(sub);
        LinearLayout actions = horizontal(); actions.setPadding(px(2), 0, 0, px(8));
        actions.addView(actionButton(T.t("Copiar todas las partidas"), "bk_all", true, this::backupAll));
        actions.addView(actionButton(T.t("Restaurar todas las partidas"), "bk_restore_all", false, this::restoreAll));
        actions.addView(actionButton(T.t("Actualizar lista"), "bk_reload", false, this::loadBackups));
        col.addView(actions);
        backupStatusView = tv(backupStatus, 25, accent, fBody); backupStatusView.setPadding(px(10), px(4), 0, px(10)); col.addView(backupStatusView);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); scroll.addView(content); col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (backupList == null) return;
        java.util.List<TirsoDrive.Backup> latest = SaveBackups.latestPerGame(backupList);
        int idx = 0;
        for (TirsoDrive.Backup b : latest) {
            final int i = idx++;
            int olderCount = 0; for (TirsoDrive.Backup x : backupList) if (x.sha.equals(b.sha)) olderCount++;
            LinearLayout row = horizontal(); row.setPadding(px(28), px(16), px(20), px(16));
            row.setBackground(cardBg(false));
            LinearLayout info = vertical();
            TextView name = tv(b.title, 32, text, fHead); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); info.addView(name);
            String consoleName = b.console; try { consoleName = Console.valueOf(b.console).title; } catch (Exception ignored) { }
            TextView meta = tv(consoleName + " · " + SaveBackups.describe(this, b) + (olderCount > 1 ? " · " + olderCount + T.t(" copias") : ""), 24, muted, fBody); meta.setPadding(0, px(6), 0, 0); info.addView(meta);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            TextView restore = actionButton(T.t("Restaurar"), "bk_r_" + i, true, () -> confirmRestore(b));
            row.addView(restore);
            if (olderCount > 1) row.addView(actionButton(T.t("Historial"), "bk_h_" + i, false, () -> showHistory(b)));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(px(8), px(8), px(8), px(8)); content.addView(row, p);
        }
    }

    // ---------------------------------------------------------------- played history
    private String dayLabel(long time) {
        java.util.Calendar now = java.util.Calendar.getInstance(), then = java.util.Calendar.getInstance(); then.setTimeInMillis(time);
        if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)) return T.t("Hoy");
        now.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)) return T.t("Ayer");
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(new java.util.Date(time));
    }


    // ------------------------------------------------------------------ phone & storage (phone app)
    private java.util.List<String> unsorted = new java.util.ArrayList<>();
    private boolean scanning;
    private void scanFolders(boolean tell) {
        if (scanning) return; scanning = true;
        new Thread(() -> {
            PhoneGames.Result r = null; try { r = PhoneGames.scan(this); } catch (Exception ignored) { }
            PhoneGames.Result res = r;
            runOnUiThread(() -> {
                scanning = false; if (res == null) return;
                rememberLibrary(); library.replaceLocal(res.getGames()); library = new Library(this); unsorted = res.getUnsorted();
                if (tell) Toast.makeText(this, res.getGames().size() + T.t(" juegos encontrados en tu teléfono") + (unsorted.isEmpty() ? "" : " · " + unsorted.size() + T.t(" sin clasificar")), Toast.LENGTH_LONG).show();
                if (page.equals("phone") || page.equals("home") || page.equals("library")) render();
            });
        }).start();
    }
    private void phoneSection(String title) { TextView t = tv(title, 24, muted, fMono); t.setLetterSpacing(.14f); t.setPadding(px(14), px(22), 0, px(6)); content.addView(t); }
    private void phoneRow(String title, String value, String tag, Runnable click) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(px(26), px(16), px(26), px(16));
        LinearLayout col = vertical(); TextView t = tv(title, 28, text, fHead); col.addView(t);
        if (value != null && !value.isEmpty()) { TextView v = tv(value, 22, muted, fBody); v.setPadding(0, px(4), 0, 0); col.addView(v); }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        focusable(row, tag, surface, click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(px(8), px(5), px(8), px(5)); content.addView(row, p);
    }
    private String onOff(boolean on) { return on ? T.t("Sí") : T.t("No"); }
    // ------------------------------------------------------------------ Settings > Home screen
    private void homeLayoutScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); content.setPadding(px(8), px(4), px(8), px(20)); scroll.addView(content);
        if (!mono) { TextView h = tv(T.t("Pantalla de inicio"), 62, text, fHead); h.setPadding(px(10), px(10), 0, px(6)); body.addView(h); }
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!BuildConfig.PHONE) {
            phoneSection(T.t("DISEÑO"));
            boolean a = HomeLayout.tvStyle(this).equals("a");
            phoneRow(T.t("Inicio: ") + (a ? T.t("Continuar y filas") : T.t("Clásico")), a ? T.t("Tarjeta Continuar, recientes, consolas y amigos") : T.t("Las pantallas de consolas de antes, según el tema"), "home_style",
                () -> { HomeLayout.tvStyle(this, a ? "classic" : "a"); render(); });
        } else {
            phoneSection(T.t("CONSOLAS"));
            boolean g = HomeLayout.consoleGrid(this);
            phoneRow(T.t("Consolas: ") + (g ? T.t("Cuadrícula") : T.t("Fila")), g ? T.t("Las primeras 9 y «Ver todo»") : T.t("Una fila que se desliza (como antes)"), "home_grid",
                () -> { HomeLayout.consoleGrid(this, !g); render(); });
        }
        phoneSection(T.t("SECCIONES"));
        String[][] names = {{"continue", T.t("Continuar")}, {"recent", T.t("Jugados recientemente")}, {"consoles", T.t("Consolas")}, {"friends", T.t("Amigos")}, {"suggested", T.t("Juegos sugeridos")}};
        for (String[] n : names) {
            if (n[0].equals("suggested") && !BuildConfig.PHONE) continue;
            boolean on = HomeLayout.shown(this, n[0]);
            phoneRow(n[1] + ": " + onOff(on), n[0].equals("suggested") ? T.t("Solo con el teléfono en vertical") : null, "home_" + n[0], () -> { HomeLayout.shown(this, n[0], !on); render(); });
        }
    }

    // ------------------------------------------------------------------ TV home, option A: Continue card + rows
    private void homeA(java.util.List<Console> visible) {
        java.util.List<Object[]> plays = library.plays();
        java.util.LinkedHashMap<String, Game> recent = new java.util.LinkedHashMap<>();
        for (Object[] r : plays) { Game g = (Game) r[0]; if (!recent.containsKey(g.id)) recent.put(g.id, g); if (recent.size() >= 12) break; }
        java.util.List<Game> rec = new java.util.ArrayList<>(recent.values());
        Game last = rec.isEmpty() ? null : rec.get(0);
        LinearLayout row = horizontal(); row.setGravity(Gravity.TOP); row.setClipChildren(false); row.setClipToPadding(false); row.setPadding(0, px(14), 0, 0);
        body.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        if (HomeLayout.shown(this, "continue")) row.addView(continueCardTv(last, visible), new LinearLayout.LayoutParams(px(740), -1));
        LinearLayout right = vertical(); right.setClipChildren(false); right.setPadding(px(40), 0, 0, 0);
        content = right;
        ScrollView sc = new ScrollView(this); sc.setClipChildren(false); sc.setClipToPadding(false); sc.addView(right);
        row.addView(sc, new LinearLayout.LayoutParams(0, -1, 1));
        if (HomeLayout.shown(this, "recent") && rec.size() > 1) {
            LinearLayout head = horizontal(); head.addView(homeSection(T.t("Jugados recientemente")), new LinearLayout.LayoutParams(0, -2, 1));
            TextView all = tv(T.t("Ver todo"), 24, accent, fBody); all.setPadding(px(14), px(6), px(14), px(6));
            focusableWith(all, "home_recent_all", f -> { GradientDrawable g = round(0, 14); if (f) g.setStroke(px(4), focusRing()); return g; }, () -> route("history"));
            head.addView(all); right.addView(head);
            LinearLayout r = horizontal(); r.setGravity(Gravity.TOP); r.setClipChildren(false); r.setClipToPadding(false); r.setPadding(px(6), px(6), px(6), px(6));
            for (Game g : rec.subList(1, rec.size())) r.addView(recentCardTv(g), new LinearLayout.LayoutParams(px(190), -2));
            right.addView(hscroll(r));
        }
        if (HomeLayout.shown(this, "consoles")) {
            right.addView(homeSection(T.t("Consolas")));
            LinearLayout r = horizontal(); r.setClipChildren(false); r.setClipToPadding(false); r.setPadding(px(6), px(6), px(6), px(6));
            for (Console c : visible) r.addView(consoleChipTv(c), new LinearLayout.LayoutParams(px(250), px(150)));
            right.addView(hscroll(r));
        }
        if (HomeLayout.shown(this, "friends") && drive.friendsChat() != 0) {
            right.addView(homeSection(T.t("Amigos")));
            int shown = 0;
            for (TirsoDrive.Friend f : drive.friends.values()) {
                if (f.userId == drive.accountId || !f.online()) continue;
                LinearLayout fr = horizontal(); fr.setPadding(px(10), px(8), px(10), px(8));
                String name = f.name.isEmpty() ? "…" : f.name;
                TextView av = tv(name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), 24, 0xFF06210F, fHead); av.setGravity(Gravity.CENTER); av.setBackground(round(f.playing() ? 0xFF22C55E : 0xFF3B82F6, 24));
                fr.addView(av, new LinearLayout.LayoutParams(px(48), px(48)));
                TextView t = tv(name + " · " + (f.playing() ? f.game : T.t("En el menú")), 26, text, fBody); t.setPadding(px(16), 0, 0, 0); fr.addView(t);
                right.addView(fr); if (++shown >= 4) break;
            }
            if (shown == 0) { TextView t = tv(T.t("Nadie conectado ahora"), 24, muted, fBody); t.setPadding(px(10), 0, 0, 0); right.addView(t); }
        }
    }
    private TextView homeSection(String title) {
        TextView t = tv(title.toUpperCase(java.util.Locale.ROOT), 21, muted, fMono); t.setLetterSpacing(.12f); t.setPadding(px(6), px(22), 0, px(8)); return t;
    }
    private android.widget.HorizontalScrollView hscroll(View inner) {
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(this); h.setHorizontalScrollBarEnabled(false); h.setClipChildren(false); h.setClipToPadding(false); h.addView(inner); return h;
    }
    private boolean filledCards() { return th.equals("console") || th.equals("tiles"); }
    private View continueCardTv(Game g, java.util.List<Console> visible) {
        Console c = g != null ? g.console : visible.isEmpty() ? Console.GBA : heroConsole(visible);
        boolean filled = filledCards();
        int radius = th.equals("tiles") ? 44 : 34;
        FrameLayout card = new FrameLayout(this); card.setClipToOutline(true);
        int ink2 = filled ? Color.WHITE : text, soft = filled ? 0xD9FFFFFF : muted;
        if (g != null) {
            FrameLayout cover = new FrameLayout(this); cover.setBackground(round(0xFFF2EEE4, 16)); cover.setClipToOutline(true);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles"); cover.setRotation(6f);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(250), px(340), Gravity.END | Gravity.CENTER_VERTICAL); cl.rightMargin = px(-14); card.addView(cover, cl);
        }
        LinearLayout texts = vertical(); texts.setPadding(px(36), px(36), px(36), 0);
        TextView k = tv(g != null ? T.t("CONTINUAR") + " · " + c.title.toUpperCase(java.util.Locale.ROOT) : T.t("EMPIEZA A JUGAR"), 21, soft, fMono); k.setLetterSpacing(.08f); texts.addView(k);
        TextView ttl = tv(g != null ? g.title : T.t("Elige una consola"), 50, ink2, fHead); ttl.setMaxLines(2); ttl.setEllipsize(android.text.TextUtils.TruncateAt.END); ttl.setPadding(0, px(12), 0, 0);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.rightMargin = px(250); texts.addView(ttl, tl);
        if (g != null && !PlayTime.label(this, g.id).isEmpty()) {
            TextView pt = tv(PlayTime.label(this, g.id) + T.t(" jugadas"), 24, ink2, fBody); pt.setPadding(px(18), px(8), px(18), px(8)); pt.setBackground(round(filled ? 0x2EFFFFFF : ov(0x14FFFFFF), 20));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2); pl.topMargin = px(18); texts.addView(pt, pl);
        }
        card.addView(texts, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        if (g != null) { TextView resume = tv("▶  " + T.t("Reanudar"), 30, filled ? 0xFF14161E : (dark ? 0xFF14161E : Color.WHITE), fHead); resume.setPadding(px(32), px(16), px(32), px(16)); resume.setBackground(round(filled ? Color.WHITE : (dark ? Color.WHITE : ink), 24));
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); rl.setMargins(px(36), 0, 0, px(36)); card.addView(resume, rl); }
        int bg = filled ? c.color : surface;
        focusableWith(card, "home_continue", f -> { GradientDrawable d = round(bg, radius); if (f) d.setStroke(px(6), focusRing()); else if (!filled) d.setStroke(px(th.equals("gba") ? 4 : 2), th.equals("gba") ? ink : c.color); return d; },
            () -> { if (g != null) startPlay(g); else openConsole(c); });
        return card;
    }
    private View recentCardTv(Game g) {
        LinearLayout cell = vertical(); cell.setPadding(px(8), px(8), px(8), px(8));
        FrameLayout art = new FrameLayout(this); art.setBackground(round(0xFFF2EEE4, 14)); art.setClipToOutline(true);
        TextView ph = tv(g.title, 20, 0xFF2B2B2B, fHead); ph.setGravity(Gravity.CENTER); ph.setPadding(px(8), px(8), px(8), px(8)); art.addView(ph, new FrameLayout.LayoutParams(-1, -1));
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); art.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        artwork.loadFirst(g, iv, "Named_Boxarts", "Named_Titles");
        cell.addView(art, new LinearLayout.LayoutParams(-1, px(230)));
        TextView t = tv(g.title, 21, text, fBody); t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setPadding(px(2), px(8), px(2), 0); cell.addView(t);
        String pt = PlayTime.label(this, g.id); if (!pt.isEmpty()) { TextView m = tv(pt, 18, muted, fBody); m.setPadding(px(2), px(2), 0, 0); cell.addView(m); }
        focusableWith(cell, "home_recent_" + g.id, f -> { GradientDrawable d = round(0, 20); if (f) d.setStroke(px(5), focusRing()); return d; }, () -> startPlay(g));
        return cell;
    }
    private View consoleChipTv(Console c) {
        boolean filled = filledCards();
        FrameLayout tile = new FrameLayout(this); tile.setPadding(px(16), px(12), px(16), px(12));
        if (logoMode()) { ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); setLogo(iv, c, filled || dark ? Color.WHITE : ink);
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, -1); il.setMargins(px(4), px(4), px(4), px(34)); tile.addView(iv, il); }
        else { TextView n = tv(c.title, 26, filled ? Color.WHITE : text, fHead); n.setGravity(Gravity.CENTER); tile.addView(n, new FrameLayout.LayoutParams(-1, -1)); }
        tile.addView(tv(c.name(), 21, filled ? Color.WHITE : text, fHead), new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        int n = library.count(c); if (n > 0) tile.addView(tv(String.valueOf(n), 18, filled ? 0xCCFFFFFF : muted, fBody), new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        int bg = filled ? c.color : surface;
        focusableWith(tile, "home_console_" + c.name(), f -> { GradientDrawable d = round(bg, th.equals("tiles") ? 30 : 22); if (f) d.setStroke(px(5), focusRing()); else if (!filled) d.setStroke(px(th.equals("gba") ? 3 : 2), th.equals("gba") ? ink : c.color); return d; },
            () -> openConsole(c));
        FrameLayout wrap = new FrameLayout(this); wrap.setPadding(px(8), 0, px(8), 0); wrap.addView(tile, new FrameLayout.LayoutParams(-1, -1)); return wrap;
    }

    private void consolesScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); content.setPadding(px(8), px(4), px(8), px(20)); scroll.addView(content);
        if (!mono) { TextView h = tv(T.t("Consolas"), 62, text, fHead); h.setPadding(px(10), px(10), 0, px(6)); body.addView(h); }
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        sSection(T.t("Consolas visibles"));
        LinearLayout row = null; int n = 0;
        for (Console cn : Console.all()) {
            if (n++ % 3 == 0) { row = sRow(); }
            boolean on = library.visible(cn);
            LinearLayout chip = horizontal(); chip.setPadding(px(22), px(12), px(22), px(12));
            View dot = new View(this); dot.setBackground(round(on ? brand(cn) : 0x44808080, 16)); chip.addView(dot, new LinearLayout.LayoutParams(px(32), px(32)));
            LinearLayout col = vertical(); col.setPadding(px(20), 0, 0, 0);
            col.addView(tv(cn.title, 25, on ? text : muted, fHead)); col.addView(tv(gameCount(library.count(cn)), 20, muted, fBody));
            chip.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            chip.addView(tv(on ? T.t("SÍ") : T.t("NO"), 24, on ? accent : muted, fMono));
            chip.setContentDescription(cn.title + ", " + (on ? "visible" : "oculta"));
            focusableWith(chip, "visible_" + cn.name(), this::cardBg, () -> { library.visible(cn, !library.visible(cn)); lastFocus = "visible_" + cn.name(); render(); });
            View.OnFocusChangeListener base = chip.getOnFocusChangeListener();
            chip.setOnFocusChangeListener((x, has) -> { base.onFocusChange(x, has); x.setScaleX(1f); x.setScaleY(1f); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, px(88), 1); p.setMargins(px(10), px(6), px(10), px(6)); row.addView(chip, p);
        }
        sFiller(row, (3 - n % 3) % 3);
        LinearLayout all = sRow(); all.setGravity(Gravity.START);
        all.addView(button(T.t("Mostrar todas"), "show_all", () -> { for (Console cn : Console.all()) library.visible(cn, true); lastFocus = "show_all"; render(); }));
    }
    /** Deletes empty "TD - " groups and channels this account created (duplicates with no game files, saves or pictures). */
    private void cleanupGroups() {
        if (!drive.ready()) { message(T.t("Limpiar grupos vacíos"), T.t("Conecta TirsoDrive primero.")); return; }
        Toast.makeText(this, T.t("Buscando grupos vacíos…"), Toast.LENGTH_SHORT).show();
        drive.emptyGroups((list, err) -> {
            if (err != null) { message(T.t("Limpiar grupos vacíos"), err); return; }
            if (list == null || list.isEmpty()) { message(T.t("Limpiar grupos vacíos"), T.t("No hay grupos vacíos que borrar.")); return; }
            StringBuilder names = new StringBuilder(); for (org.drinkless.tdlib.TdApi.Chat c : list) names.append("• ").append(c.title).append("\n");
            new AlertDialog.Builder(this).setTitle(T.t("Borrar ") + list.size() + T.t(" grupos vacíos"))
                .setMessage(T.t("Estos grupos los creaste tú y no tienen juegos, partidas ni capturas. Se borrarán de Telegram:\n\n") + names)
                .setPositiveButton(T.t("Borrar"), (d, w) -> drive.deleteGroups(list, (n, e2) -> message(T.t("Limpiar grupos vacíos"), e2 != null ? e2 : n + T.t(" grupos borrados."))))
                .setNegativeButton(T.t("Cancelar"), null).show();
        });
    }
    private void phoneScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); content.setPadding(px(8), px(4), px(8), px(20)); scroll.addView(content);
        if (!mono) { TextView h = tv(T.t("Teléfono y almacenamiento"), 62, text, fHead); h.setPadding(px(10), px(10), 0, px(6)); body.addView(h); }
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        phoneSection(T.t("CARPETAS DEL TELÉFONO"));
        if (!PhoneGames.canReadFolders()) phoneRow(T.t("Permitir acceso a los archivos"), T.t("Necesario para encontrar juegos en tus carpetas"), "allow_files", () -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
        });
        phoneRow(T.t("Buscar juegos en carpetas: ") + onOff(PhoneGames.scanOn(this)), T.t("Al abrir la app y con «Buscar ahora»"), "scan_on", () -> { PhoneGames.scanOn(this, !PhoneGames.scanOn(this)); render(); });
        for (String f : PhoneGames.folders(this)) phoneRow(f, T.t("Toca para quitar esta carpeta"), "folder_" + f.hashCode(), () -> new AlertDialog.Builder(this).setTitle(T.t("Quitar carpeta")).setMessage(f)
            .setPositiveButton(T.t("Quitar"), (d, w) -> { PhoneGames.removeFolder(this, f); scanFolders(false); render(); }).setNegativeButton(T.t("Cancelar"), null).show());
        phoneRow(T.t("Añadir carpeta"), T.t("Elige una carpeta con juegos (se buscan también sus subcarpetas)"), "add_folder", () -> {
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 33); } catch (Exception e) { Toast.makeText(this, T.t("No hay un selector de archivos en este teléfono."), Toast.LENGTH_LONG).show(); }
        });
        phoneRow(scanning ? T.t("Buscando…") : T.t("Buscar ahora"), library.games.stream().filter(g -> g.id.startsWith("local:")).count() + T.t(" juegos del teléfono en la biblioteca"), "scan_now", () -> {
            if (!PhoneGames.canReadFolders()) { Toast.makeText(this, T.t("Primero permite el acceso a los archivos."), Toast.LENGTH_LONG).show(); return; }
            scanFolders(true); render(); });
        for (String u : unsorted) phoneRow(new java.io.File(u).getName(), T.t("Sin clasificar · toca para elegir la consola"), "unsorted_" + u.hashCode(), () -> {
            Console[] all = Console.all(); String[] names = new String[all.length]; for (int i = 0; i < all.length; i++) names[i] = all[i].title;
            new AlertDialog.Builder(this).setTitle(new java.io.File(u).getName()).setItems(names, (d, w) -> { PhoneGames.choose(this, u, all[w]); scanFolders(false); }).show();
        });
        phoneSection(T.t("JUEGOS DE TELEGRAM"));
        phoneRow(T.t("Guardar juegos descargados: ") + onOff(PhoneGames.keep(this)), T.t("Se quedan en el teléfono para jugar sin volver a descargarlos"), "keep_games", () -> { PhoneGames.keep(this, !PhoneGames.keep(this)); render(); });
        phoneRow(T.t("Descargar solo con Wi-Fi: ") + onOff(PhoneGames.wifiOnly(this)), T.t("No usa datos móviles para descargar juegos"), "wifi_only", () -> { PhoneGames.wifiOnly(this, !PhoneGames.wifiOnly(this)); render(); });
        java.util.List<Object[]> kept = PhoneGames.kept(this); long total = 0; for (Object[] k : kept) total += (Long) k[4];
        if (!kept.isEmpty()) {
            phoneRow(T.t("Borrar todos los juegos guardados"), android.text.format.Formatter.formatFileSize(this, total) + T.t(" en uso"), "kept_all", () -> new AlertDialog.Builder(this).setTitle(T.t("Borrar juegos guardados"))
                .setMessage(T.t("Se borran del teléfono; siguen en Telegram.")).setPositiveButton(T.t("Borrar"), (d, w) -> { for (Object[] k : PhoneGames.kept(this)) deleteKept(k); render(); }).setNegativeButton(T.t("Cancelar"), null).show());
            for (Object[] k : kept) {
                String id = (String) k[0]; String title = new java.io.File((String) k[3]).getName();
                for (Game g : library.games) if (g.id.equals(id)) { title = g.title + " · " + g.console.name(); break; }
                String shown = title;
                phoneRow(shown, android.text.format.Formatter.formatFileSize(this, (Long) k[4]) + T.t(" · toca para borrar"), "kept_" + id.hashCode(), () -> new AlertDialog.Builder(this).setTitle(shown)
                    .setMessage(T.t("Se borra del teléfono; sigue en Telegram.")).setPositiveButton(T.t("Borrar"), (d, w) -> { deleteKept(k); render(); }).setNegativeButton(T.t("Cancelar"), null).show());
            }
        }
        phoneSection(T.t("RENDIMIENTO"));
        phoneRow(T.t("Modo de rendimiento: ") + (PhoneGames.perfMax(this) ? T.t("Máximo") : T.t("Equilibrado")), PhoneGames.perfMax(this) ? T.t("Toda la potencia del teléfono") : T.t("Más frío y estable en sesiones largas"), "perf_mode", () -> { PhoneGames.perfMax(this, !PhoneGames.perfMax(this)); render(); });
        phoneSection(T.t("PANTALLA"));
        phoneRow(T.t("Bloquear en horizontal: ") + onOff(PhoneGames.lockLandscape(this)), T.t("La app no gira al inclinar el teléfono"), "lock_landscape", () -> { PhoneGames.lockLandscape(this, !PhoneGames.lockLandscape(this)); render(); });
    }
    private void deleteKept(Object[] k) {
        PhoneGames.forget(this, (String) k[0]);
        drive.releaseRom((Integer) k[1], (Long) k[2]);
        new java.io.File((String) k[3]).delete();
    }
    private void gpuDriver() {
        String now = PhoneGames.driverName(this);
        String[] items = {T.t("Elegir controlador (.zip)"), T.t("Usar el del teléfono")};
        new AlertDialog.Builder(this).setTitle(T.t("Controlador de GPU") + (now.isEmpty() ? "" : " · " + now))
            .setMessage(T.t("Para PS2, GameCube y Wii (Vulkan). Elige un ZIP de controlador como Turnip de Mr. Purple."))
            .setItems(items, (d, w) -> {
                if (w == 0) { try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 34); } catch (Exception e) { Toast.makeText(this, T.t("No hay un selector de archivos en este teléfono."), Toast.LENGTH_LONG).show(); } }
                else { PhoneGames.removeDriver(this); render(); }
            }).setNegativeButton(T.t("Cancelar"), null).show();
    }
    // ------------------------------------------------------------------ friends
    private Runnable friendsRender;
    private void friendsRefreshSoon() {
        if (friendsRender != null) return;
        friendsRender = () -> { friendsRender = null; if (page.equals("friends")) render(); };
        root.postDelayed(friendsRender, 500);
    }
    private String sinceText(long since) {
        long m = Math.max(0, (System.currentTimeMillis() - since) / 60000);
        return m < 1 ? T.t("ahora") : m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }
    private void friendRow(TirsoDrive.Friend f, boolean you) {
        String name = f.name.isEmpty() ? "…" : f.name;
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(px(22), px(14), px(22), px(14));
        int color = f.playing() ? 0xFF22C55E : f.online() ? 0xFF3B82F6 : 0xFF6B7280;
        TextView av = tv(name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), 34, Color.WHITE, fHead); av.setGravity(Gravity.CENTER);
        av.setBackground(gradient(shade(color, 1f), shade(color, .7f), 40, GradientDrawable.Orientation.TL_BR));
        row.addView(av, new LinearLayout.LayoutParams(px(72), px(72)));
        LinearLayout col = vertical(); col.setPadding(px(22), 0, 0, 0);
        TextView n = tv(name + (you ? T.t("  (tú)") : ""), 30, text, fHead); n.setSingleLine(true); n.setEllipsize(android.text.TextUtils.TruncateAt.END); col.addView(n);
        String sub = f.playing() ? f.game : f.online() ? T.t("En el menú") : T.t("Desconectado");
        TextView st = tv(sub, 24, f.playing() ? color : muted, fBody); st.setSingleLine(true); st.setEllipsize(android.text.TextUtils.TruncateAt.END); st.setPadding(0, px(4), 0, 0); col.addView(st);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (f.playing()) {
            Console c = null; try { c = Console.valueOf(f.console); } catch (Exception e) { }
            TextView badge = tv((c == null ? f.console : c.name()) + " · " + sinceText(f.since), 22, Color.WHITE, fMono); badge.setPadding(px(16), px(8), px(16), px(8));
            badge.setBackground(round(c == null ? 0xFF475569 : c.color, 14)); row.addView(badge);
        }
        focusable(row, "friend_" + f.userId, surface, () -> { });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(px(8), px(6), px(8), px(6)); content.addView(row, p);
    }
    private void friendSection(String title, int count) {
        TextView t = tv(title + " · " + count, 24, muted, fMono); t.setLetterSpacing(.14f); t.setPadding(px(14), px(18), 0, px(6)); content.addView(t);
    }
    private void friendsScreen() {
        drive.friendsListener(this::friendsRefreshSoon);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); content.setPadding(px(8), px(4), px(8), px(20)); scroll.addView(content);
        if (!mono) { TextView h = tv(T.t("Amigos"), 62, text, fHead); h.setPadding(px(10), px(10), 0, px(10)); body.addView(h); }
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!drive.ready()) { content.addView(tv(T.t("Conecta TirsoDrive para ver a tus amigos."), 30, muted, fBody)); return; }
        if (drive.friendsChat() == 0) {
            content.addView(tv(T.t("Mira quién está conectado y a qué juega. Todos entran al mismo grupo privado de Telegram."), 28, muted, fBody));
            content.addView(button(T.t("Pegar enlace de invitación"), "friends_join", this::friendsJoinDialog));
            content.addView(button(T.t("Crear grupo de amigos"), "friends_create", () -> {
                Toast.makeText(this, T.t("Creando grupo…"), Toast.LENGTH_SHORT).show();
                drive.friendsCreate((link, error) -> { if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show(); else { Toast.makeText(this, T.t("Grupo creado. Comparte el enlace con tus amigos."), Toast.LENGTH_LONG).show(); render(); } }); 
            }));
            return;
        }
        java.util.ArrayList<TirsoDrive.Friend> playing = new java.util.ArrayList<>(), online = new java.util.ArrayList<>(), offline = new java.util.ArrayList<>();
        for (TirsoDrive.Friend f : drive.friends.values()) (f.playing() ? playing : f.online() ? online : offline).add(f);
        java.util.Comparator<TirsoDrive.Friend> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
        playing.sort(byName); online.sort(byName); offline.sort(byName);
        if (playing.size() + online.size() + offline.size() <= 1) content.addView(tv(T.t("Aún no hay amigos aquí. Comparte el enlace de invitación."), 28, muted, fBody));
        if (!playing.isEmpty()) { friendSection(T.t("JUGANDO AHORA"), playing.size()); for (TirsoDrive.Friend f : playing) friendRow(f, f.userId == drive.accountId); }
        if (!online.isEmpty()) { friendSection(T.t("EN LÍNEA"), online.size()); for (TirsoDrive.Friend f : online) friendRow(f, f.userId == drive.accountId); }
        if (!offline.isEmpty()) { friendSection(T.t("DESCONECTADOS"), offline.size()); for (TirsoDrive.Friend f : offline) friendRow(f, f.userId == drive.accountId); }
        LinearLayout actions = horizontal(); actions.setPadding(0, px(24), 0, 0);
        actions.addView(button(drive.shareActivity() ? T.t("Mostrar mi actividad: sí") : T.t("Mostrar mi actividad: no"), "friends_share", () -> { drive.shareActivity(!drive.shareActivity()); render(); }));
        actions.addView(button(T.t("Actualizar"), "friends_refresh", () -> { drive.loadFriends(); Toast.makeText(this, T.t("Actualizando…"), Toast.LENGTH_SHORT).show(); }));
        if (!drive.friendsLink().isEmpty()) actions.addView(button(T.t("Copiar enlace"), "friends_copy", () -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("RetroStream", drive.friendsLink()));
            Toast.makeText(this, T.t("Enlace copiado: ") + drive.friendsLink(), Toast.LENGTH_LONG).show();
        }));
        actions.addView(button(T.t("Salir del grupo"), "friends_leave", () -> new AlertDialog.Builder(this).setTitle(T.t("Salir de amigos"))
            .setMessage(T.t("Dejarás de ver y de mostrar actividad. Puedes volver a unirte con el enlace."))
            .setPositiveButton(T.t("Salir"), (d, w) -> { drive.friendsLeave(); render(); }).setNegativeButton(T.t("Cancelar"), null).show()));
        content.addView(actions);
    }
    private void friendsJoinDialog() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("https://t.me/+…");
        new AlertDialog.Builder(this).setTitle(T.t("Enlace de invitación")).setView(input)
            .setPositiveButton(T.t("Unirme"), (d, w) -> drive.friendsJoin(input.getText().toString(), (id, error) -> {
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show(); else render();
            })).setNegativeButton(T.t("Cancelar"), null).show();
    }
    private void historyScreen() {
        LinearLayout col = vertical(); body.addView(col, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!mono) { TextView h = tv(T.t("Historial de juego"), 62, text, fHead); h.setPadding(px(10), px(10), 0, px(10)); col.addView(h); }
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); content.setPadding(px(8), px(4), px(8), px(20)); scroll.addView(content); col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        java.util.List<Object[]> plays = library.plays();
        if (plays.isEmpty()) { content.addView(tv(T.t("Aún no has jugado a nada."), 32, muted, fBody)); return; }
        TextView recentTitle = tv(T.t("Jugados recientemente"), 26, muted, fMono); recentTitle.setLetterSpacing(.14f); recentTitle.setPadding(px(6), px(6), 0, px(14)); content.addView(recentTitle);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.setClipChildren(false); hs.setClipToPadding(false);
        LinearLayout strip = horizontal(); strip.setClipChildren(false); hs.addView(strip);
        java.util.HashSet<String> seen = new java.util.HashSet<>(); int shown = 0;
        for (Object[] play : plays) {
            Game game = (Game) play[0]; if (!seen.add(game.id) || shown >= 8) continue;
            LinearLayout cell = vertical(); cell.setPadding(px(7), px(7), px(7), px(7));
            FrameLayout art = new FrameLayout(this); art.setBackground(round(Color.WHITE, 22));
            TextView ph = tv(game.title, 26, 0xFF1A1A2E, fHead); ph.setGravity(Gravity.CENTER); ph.setPadding(px(16), px(12), px(16), px(12)); ph.setMaxLines(3); ph.setEllipsize(android.text.TextUtils.TruncateAt.END);
            art.addView(ph, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setPadding(px(14), px(12), px(14), px(12));
            art.addView(image, new FrameLayout.LayoutParams(-1, -1)); artwork.loadFirst(game, image, "Named_Logos", "Named_Titles", "Named_Boxarts");
            cell.addView(art, new LinearLayout.LayoutParams(px(330), px(220)));
            TextView when = tv(game.console.name() + " · " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date((Long) play[1])), 22, muted, fMono); when.setPadding(px(6), px(10), 0, 0); cell.addView(when);
            final int ringColor = focusRing(); final int idx = shown;
            focusableWith(cell, "hist_recent_" + idx, f -> { GradientDrawable g = round(0, 28); if (f) g.setStroke(px(6), ringColor); return g; }, () -> startPlay(game));
            strip.addView(cell, new LinearLayout.LayoutParams(-2, -2)); shown++;
        }
        content.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        String lastDay = "";
        for (int i = 0; i < plays.size(); i++) {
            Game game = (Game) plays.get(i)[0]; long time = (Long) plays.get(i)[1];
            String day = dayLabel(time);
            if (!day.equals(lastDay)) { lastDay = day; TextView d = tv(day.toUpperCase(java.util.Locale.ROOT), 26, muted, fMono); d.setLetterSpacing(.14f); d.setPadding(px(6), px(28), 0, px(12)); content.addView(d); }
            LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(px(22), px(14), px(26), px(14));
            TextView badge = tv(game.console.name(), 22, Color.WHITE, fMono); badge.setPadding(px(14), px(6), px(14), px(6)); badge.setBackground(round(brand(game.console), 12)); row.addView(badge);
            TextView name = tv(game.title, 30, text, fHead); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(px(22), 0, px(12), 0); row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(tv(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(time)), 26, muted, fMono));
            final int ringColor = focusRing();
            focusableWith(row, "hist_" + i, f -> { GradientDrawable g = round(th.equals("midnight") ? ov(0x14FFFFFF) : surface, 22); if (f) g.setStroke(px(5), ringColor); else if (!th.equals("midnight")) g.setStroke(px(2), strokeColor); return g; }, () -> startPlay(game));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.setMargins(0, px(5), 0, px(5)); content.addView(row, rp);
        }
    }


    // ---------------------------------------------------------------- resolution per console
    private void resolutionScreen() {
        LinearLayout col = vertical(); body.addView(col, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!mono) { TextView h = tv(T.t("Resolución"), 62, text, fHead); h.setPadding(px(10), px(10), 0, 0); col.addView(h); }
        TextView sub = tv(T.t("Elige la resolución de cada consola. En N64 cambia el tamaño del dibujo; en las consolas 2D cambia cómo se escala la imagen."), 26, muted, fBody); sub.setPadding(px(10), px(8), px(10), px(16)); col.addView(sub);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); content.setPadding(0, px(4), 0, px(20)); scroll.addView(content); col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        resolutionRow(null);
        for (Console c : Console.all()) if (library.visible(c) && c != Console.MAME) resolutionRow(c);
    }
    private void resolutionRow(Console c) {
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(px(26), px(14), px(18), px(14));
        GradientDrawable bg = round(th.equals("midnight") ? ov(0x10FFFFFF) : surface, 22); if (!th.equals("midnight")) bg.setStroke(px(2), strokeColor); row.setBackground(bg);
        LinearLayout names = vertical();
        names.addView(tv(c == null ? T.t("Todas las consolas") : c.title, 32, text, fHead));
        String note = c == null ? T.t("Cambia todas a la vez") : c == Console.N64 ? T.t("Tamaño del dibujo") : c == Console.NDS ? T.t("Solo con el motor DeSmuME") : T.t("Escalado de la imagen");
        TextView n = tv(note, 22, muted, fBody); n.setPadding(0, px(4), 0, 0); names.addView(n);
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        String[] ids = {"low", "medium", "high"}, labels = {T.t("Baja"), T.t("Media"), T.t("Alta")};
        String current = c == null ? null : Presets.resolution(this, c);
        if (c == null) { boolean same = true; String first = null; for (Console x : Console.all()) { if (!library.visible(x) || x == Console.MAME) continue; String v = Presets.resolution(this, x); if (first == null) first = v; else if (!first.equals(v)) same = false; } current = same ? first : null; }
        for (int i = 0; i < 3; i++) {
            final String id = ids[i]; boolean on = id.equals(current); final String tag = "res_" + (c == null ? "ALL" : c.name()) + "_" + id;
            TextView chip = tv(labels[i], 26, on ? onAccent() : text, fHead); chip.setGravity(Gravity.CENTER); chip.setPadding(px(30), px(14), px(30), px(14));
            final int ringColor = focusRing();
            focusableWith(chip, tag, f -> { GradientDrawable g = round(on ? (th.equals("midnight") ? accent : th.equals("tiles") ? 0xFFFFC857 : ink) : (th.equals("midnight") ? ov(0x18FFFFFF) : ov(0x14FFFFFF)), 18); if (f) g.setStroke(px(5), ringColor); return g; },
                () -> { if (c == null) { for (Console x : Console.all()) Presets.setResolution(this, x, id); } else Presets.setResolution(this, c, id); lastFocus = tag; render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(150), px(72)); cp.setMargins(px(6), 0, px(6), 0); row.addView(chip, cp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.setMargins(px(8), px(7), px(8), px(7)); content.addView(row, rp);
    }

    // ---------------------------------------------------------------- download all artwork
    private final java.util.Set<Console> artSel = new java.util.LinkedHashSet<>(); private boolean artInit, artRunning;
    private java.util.concurrent.atomic.AtomicBoolean artCancel = new java.util.concurrent.atomic.AtomicBoolean();
    private String artText = ""; private int artDone, artTotal; private TextView artStatusView; private android.widget.ProgressBar artBar;
    private void artworkScreen() {
        if (!artInit) { artInit = true; for (Console c : Console.all()) if (library.visible(c) && library.count(c) > 0) artSel.add(c); }
        LinearLayout col = vertical(); body.addView(col, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!mono) { TextView h = tv(T.t("Descargar imágenes"), 62, text, fHead); h.setPadding(px(10), px(10), 0, 0); col.addView(h); }
        TextView sub = tv(T.t("Elige las consolas de las que descargar logos y carátulas. Se guardan en la TV."), 26, muted, fBody); sub.setPadding(px(10), px(8), 0, px(16)); col.addView(sub);
        LinearLayout actions = horizontal(); actions.setPadding(px(2), 0, 0, px(8));
        actions.addView(actionButton(T.t("Seleccionar todo"), "art_all", false, () -> { for (Console c : Console.all()) if (library.visible(c) && library.count(c) > 0) artSel.add(c); lastFocus = "art_all"; render(); }));
        actions.addView(actionButton(T.t("Ninguna"), "art_none", false, () -> { artSel.clear(); lastFocus = "art_none"; render(); }));
        col.addView(actions);
        ScrollView scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setFillViewport(true); activeScroll = scroll;
        content = vertical(); content.setClipChildren(false); content.setPadding(0, px(6), 0, px(20)); scroll.addView(content); col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout row = null; int n = 0, selectedGames = 0;
        for (Console c : Console.all()) {
            if (!library.visible(c)) continue;
            boolean on = artSel.contains(c); int count = library.count(c); if (on) selectedGames += count;
            if (n++ % 3 == 0) { row = horizontal(); content.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
            LinearLayout cell = horizontal(); cell.setGravity(Gravity.CENTER_VERTICAL); cell.setPadding(px(26), px(18), px(26), px(18));
            TextView mark = tv(on ? "✓" : "", 34, Color.WHITE, fHead); mark.setGravity(Gravity.CENTER); mark.setBackground(round(on ? brand(c) : 0x33888888, 10)); cell.addView(mark, new LinearLayout.LayoutParams(px(50), px(50)));
            TextView name = tv(c.name(), 32, text, fHead); name.setPadding(px(20), 0, 0, 0); cell.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            cell.addView(tv(String.valueOf(count), 28, muted, fMono));
            final int ringColor = focusRing();
            focusableWith(cell, "art_" + c.name(), f -> { GradientDrawable g = round(th.equals("midnight") ? ov(0x14FFFFFF) : surface, 22); if (f) g.setStroke(px(5), ringColor); else g.setStroke(px(on ? 4 : 2), on ? brand(c) : strokeColor); return g; },
                () -> { if (on) artSel.remove(c); else artSel.add(c); lastFocus = "art_" + c.name(); render(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.setMargins(px(8), px(8), px(8), px(8)); row.addView(cell, cp);
        }
        while (row != null && n++ % 3 != 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        artStatusView = tv(artText, 26, accent, fBody); artStatusView.setPadding(px(10), px(22), 0, px(8)); content.addView(artStatusView);
        artBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); artBar.setMax(Math.max(1, artTotal)); artBar.setProgress(artDone);
        artBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent)); artBar.setVisibility(artRunning ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, px(14)); bp.setMargins(px(10), 0, px(10), px(18)); content.addView(artBar, bp);
        final int chosen = artSel.size();
        TextView go = actionButton(artRunning ? T.t("Cancelar descarga") : T.t("Descargar imágenes de ") + chosen + T.t(" consolas"), "art_go", !artRunning, this::toggleArtworkDownload);
        content.addView(go);
    }
    private void toggleArtworkDownload() {
        if (artRunning) { artCancel.set(true); return; }
        java.util.ArrayList<Game> list = new java.util.ArrayList<>();
        for (Game g : library.games) if (artSel.contains(g.console) && library.visible(g.console)) list.add(g);
        if (list.isEmpty()) { artText = T.t("Elige al menos una consola"); lastFocus = "art_go"; render(); return; }
        artRunning = true; artCancel = new java.util.concurrent.atomic.AtomicBoolean(); artDone = 0; artTotal = list.size(); artText = T.t("Descargando…"); lastFocus = "art_go"; render();
        artwork.prefetch(list, artCancel, (done, total, found) -> {
            artDone = done; artTotal = total; artText = T.t("Descargando…") + " " + done + " / " + total + " · " + found + T.t(" con imagen");
            if (page.equals("artwork") && artStatusView != null && artBar != null) { artStatusView.setText(artText); artBar.setMax(Math.max(1, total)); artBar.setProgress(done); }
        }, () -> {
            artRunning = false; artText = T.t("Descarga terminada") + " · " + artDone + " / " + artTotal;
            if (!isFinishing() && !isDestroyed() && page.equals("artwork")) { lastFocus = "art_go"; render(); }
        });
    }
    private void confirmRestore(TirsoDrive.Backup b) {
        new AlertDialog.Builder(this).setTitle(T.t("Restaurar partidas")).setMessage(b.title + "\n" + SaveBackups.describe(this, b) + "\n\n" + T.t("Se reemplazarán las partidas actuales de este juego en esta TV."))
            .setPositiveButton(T.t("Restaurar"), (d, w) -> {
                setBackupStatus(T.t("Restaurando…"));
                SaveBackups.restore(this, drive, b, (ok, msg) -> { setBackupStatus(b.title + ": " + msg); return kotlin.Unit.INSTANCE; });
            }).setNegativeButton(T.t("Cancelar"), null).show();
    }
    private void showHistory(TirsoDrive.Backup latest) {
        java.util.ArrayList<TirsoDrive.Backup> list = new java.util.ArrayList<>();
        for (TirsoDrive.Backup x : backupList) if (x.sha.equals(latest.sha)) list.add(x);
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (i == 0 ? T.t("Última") + " · " : "") + SaveBackups.describe(this, list.get(i));
        new AlertDialog.Builder(this).setTitle(latest.title).setItems(labels, (d, w) -> confirmRestore(list.get(w))).setNegativeButton(T.t("Cerrar"), null).show();
    }
    private void backupAll() {
        if (backupBusy) return; backupBusy = true;
        setBackupStatus(T.t("Preparando copias…"));
        SaveBackups.uploadAll(this, drive, p -> { setBackupStatus(T.t("Subiendo ") + p); return kotlin.Unit.INSTANCE; },
            (ok, msg) -> { backupBusy = false; setBackupStatus(msg); loadBackupsQuiet(); return kotlin.Unit.INSTANCE; });
    }
    private void loadBackupsQuiet() {
        SaveBackups.fetch(drive, null, (list, err) -> { if (list != null) { backupList = list; if (page.equals("backups")) { String keep = backupStatus; render(); setBackupStatus(keep); } } return kotlin.Unit.INSTANCE; });
    }
    private void restoreAll() {
        if (backupBusy) return;
        if (backupList == null || backupList.isEmpty()) { setBackupStatus(T.t("No hay copias que restaurar.")); return; }
        java.util.List<TirsoDrive.Backup> latest = SaveBackups.latestPerGame(backupList);
        new AlertDialog.Builder(this).setTitle(T.t("Restaurar todas las partidas")).setMessage(latest.size() + T.t(" juegos se restaurarán con su copia más reciente. Se reemplazarán las partidas actuales de esos juegos."))
            .setPositiveButton(T.t("Restaurar"), (d, w) -> { backupBusy = true; restoreNext(latest, 0, 0); }).setNegativeButton(T.t("Cancelar"), null).show();
    }
    private void restoreNext(java.util.List<TirsoDrive.Backup> list, int i, int ok) {
        if (i >= list.size()) { backupBusy = false; setBackupStatus(ok + "/" + list.size() + " " + T.t("juegos restaurados")); return; }
        setBackupStatus(T.t("Restaurando ") + (i + 1) + "/" + list.size() + " · " + list.get(i).title);
        SaveBackups.restore(this, drive, list.get(i), (success, msg) -> { restoreNext(list, i + 1, ok + (success ? 1 : 0)); return kotlin.Unit.INSTANCE; });
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
    /** Phone app: each person adds their own BIOS (PS2 needs one); FDS and MAME files as before. */
    private void biosFiles(){
        java.io.File ps2=Bios.ps2(this);
        String[] items={T.t("BIOS de PS2: ")+(ps2!=null?ps2.getName():T.t("no añadida")),T.t("Quitar la BIOS de PS2"),T.t("Otros archivos (Famicom Disk System, MAME)")};
        new AlertDialog.Builder(this).setTitle(T.t("Archivos BIOS")).setItems(items,(d,w)->{
            if(w==0){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),32);}catch(android.content.ActivityNotFoundException e){message(T.t("Archivos BIOS"),T.t("No hay un selector de archivos en este teléfono."));}}
            else if(w==1){Bios.removePs2(this);render();}
            else importSystemFile();
        }).setNegativeButton(T.t("Cancelar"),null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        if(request==33){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
            String path=PhoneGames.pathOf(data.getData());if(path==null){Toast.makeText(this,T.t("No se pudo usar esa carpeta."),Toast.LENGTH_LONG).show();return;}
            PhoneGames.addFolder(this,path);if(PhoneGames.canReadFolders())scanFolders(true);render();return;}
        if(request==34){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;android.net.Uri uri=data.getData();
            String name="driver";try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}catch(Exception ignored){}
            String fname=name;new Thread(()->{String err;try{err=PhoneGames.addDriver(this,uri,fname);}catch(Exception e){err=e.getMessage();}String fe=err;
                runOnUiThread(()->{Toast.makeText(this,fe==null?T.t("Controlador de GPU instalado"):fe,Toast.LENGTH_LONG).show();render();});}).start();return;}
        if(request==32){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;android.net.Uri uri=data.getData();
            new Thread(()->{String err;try{err=Bios.addPs2(this,uri);}catch(Exception e){err=e.getMessage();}String fe=err;
                runOnUiThread(()->{Toast.makeText(this,fe==null?T.t("BIOS de PS2 añadida"):fe,Toast.LENGTH_LONG).show();render();});}).start();return;}
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
        if (embedded != null && (page.equals(embedded) || page.equals("home") || page.equals("welcome"))) { finish(); return; }
        if(page.equals("welcome")){drive.skip();route("home");return;}
        if (page.equals("loading")) backToLibrary();
        else if (page.equals("backups") || page.equals("artwork") || page.equals("resolution") || page.equals("phone") || page.equals("consoles") || page.equals("home_layout")) route("settings");
        else if (!page.equals("home")) route("home");
        else new AlertDialog.Builder(this).setTitle(T.t("Salir de RetroStream"))
                    .setPositiveButton(T.t("Salir"), (d,w) -> finish()).setNegativeButton(T.t("Cancelar"), null).show();
    }
    @Override protected void onDestroy() {drive.detach(driveListener);inputManager.unregisterInputDeviceListener(inputListener);artCancel.set(true); artwork.close(); if (isFinishing() && embedded == null) { drive.releaseAll(); TemporaryRoms.purge(getApplicationContext()); } super.onDestroy(); }
    @Override protected void onPause(){cancelPending();super.onPause();}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);if(root!=null)render();}
    @Override protected void onStart(){super.onStart();if(drive!=null)drive.appVisible(true);}
    @Override protected void onStop(){if(drive!=null)drive.appVisible(false);super.onStop();}
    @Override protected void onResume(){
        super.onResume();
        if(BuildConfig.PHONE&&page!=null&&page.equals("phone")&&root!=null)render();   // back from the file access setting
        if(drive!=null)Screenshots.uploadPending(this,drive);
        if(drive!=null)drive.inMenu();
        // Saves changed while playing are copied to the Telegram channel in the background.
        if(drive!=null&&drive.ready()&&page!=null&&!page.equals("welcome"))SaveBackups.uploadAll(this,drive,p->kotlin.Unit.INSTANCE,(ok,msg)->kotlin.Unit.INSTANCE);
    }
}
