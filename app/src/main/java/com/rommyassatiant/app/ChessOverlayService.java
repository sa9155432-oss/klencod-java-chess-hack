package com.rommyassatiant.app;

import android.accessibilityservice.AccessibilityService;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.*;

import java.nio.ByteBuffer;
import java.util.Locale;

// ===================================================================
// ChessHelperOverlay: floating menu + screen-capture based FEN reader.
// Not an Xposed hook -- this app reads pixels via MediaProjection, so it
// works on any chess app/site (Chess.com app, browser, etc) without root.
// ===================================================================
public class ChessOverlayService extends Service {

    private static final String TAG = "ChessHelper";
    private static final int CAPTURE_INTERVAL_MS = 1500;

    // FILL after your first run: tap "Calibrate corners" in the overlay, tap the
    // top-left square (a8) then bottom-right square (h1) of the board on screen.
    // These get set from that tap, not hardcoded -- see calibrateCorners().
    private int boardLeft = -1, boardTop = -1, boardSize = -1;
    private boolean youAreWhite = true;

    private WindowManager wm;
    private View overlayRoot;
    private TextView statusText;
    private Button toggleBtn, calibrateBtn, colorBtn;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean calibrating = false;
    private int calibStep = 0; // 0 = waiting a8 tap, 1 = waiting h1 tap

    private int screenW, screenH, screenDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels; screenH = dm.heightPixels; screenDpi = dm.densityDpi;
        buildOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // started with the MediaProjection result from MainActivity
        if (intent != null && intent.hasExtra("resultCode")) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mgr.getMediaProjection(resultCode, data);
            setupVirtualDisplay();
        }
        return START_STICKY;
    }

    // ---------- floating window ----------
    private void buildOverlay() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.argb(230, 25, 25, 30));
        box.setPadding(20, 20, 20, 20);

        statusText = new TextView(this);
        statusText.setTextColor(Color.WHITE);
        statusText.setText("calibrate the board first");
        box.addView(statusText);

        LinearLayout row1 = new LinearLayout(this);
        calibrateBtn = new Button(this);
        calibrateBtn.setText("CALIBRATE");
        calibrateBtn.setOnClickListener(v -> startCalibration());
        colorBtn = new Button(this);
        colorBtn.setText("SIDE: WHITE");
        colorBtn.setOnClickListener(v -> {
            youAreWhite = !youAreWhite;
            colorBtn.setText(youAreWhite ? "SIDE: WHITE" : "SIDE: BLACK");
        });
        row1.addView(calibrateBtn);
        row1.addView(colorBtn);
        box.addView(row1);

        toggleBtn = new Button(this);
        toggleBtn.setText("START ANALYSIS");
        toggleBtn.setOnClickListener(v -> {
            running = !running;
            toggleBtn.setText(running ? "STOP ANALYSIS" : "START ANALYSIS");
            if (running) scheduleCapture();
        });
        box.addView(toggleBtn);

        // transparent full-screen tap catcher, only active while calibrating,
        // so you can tap two corners directly on the real board underneath
        overlayRoot = box;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 20; lp.y = 100;

        // draggable: move the panel itself so it never blocks the board
        box.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = lp.x - e.getRawX(); dy = lp.y - e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = (int) (e.getRawX() + dx); lp.y = (int) (e.getRawY() + dy);
                        wm.updateViewLayout(box, lp); return true;
                }
                return false;
            }
        });

        wm.addView(box, lp);
    }

    private void startCalibration() {
        calibrating = true; calibStep = 0;
        statusText.setText("tap the CENTER of square a8 (top-left of your view)");
        addTapCatcher();
    }

    // full-screen invisible view that only exists during calibration, so your
    // two taps land on actual board coordinates instead of the menu
    private View tapCatcher;
    private void addTapCatcher() {
        if (tapCatcher != null) return;
        tapCatcher = new View(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        tapCatcher.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                if (calibStep == 0) {
                    boardLeft = (int) e.getRawX(); boardTop = (int) e.getRawY();
                    calibStep = 1;
                    statusText.setText("now tap the CENTER of square h1 (bottom-right)");
                } else {
                    int right = (int) e.getRawX(), bottom = (int) e.getRawY();
                    // corners are square CENTERS, so the board spans one half-square beyond each
                    int half = Math.abs(right - boardLeft) / 14; // (7 squares between centers)/2
                    boardLeft -= half; boardTop -= half;
                    boardSize = Math.abs(right - boardLeft) + half * 2;
                    calibrating = false;
                    wm.removeView(tapCatcher); tapCatcher = null;
                    statusText.setText(String.format(Locale.US,
                            "calibrated: %dpx board at (%d,%d)", boardSize, boardLeft, boardTop));
                }
            }
            return true;
        });
        wm.addView(tapCatcher, lp);
    }

    // ---------- screen capture ----------
    private void setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay("ChessCap", screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);
    }

    private void scheduleCapture() {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (boardSize > 0) {
                    Bitmap bmp = grabFrame();
                    if (bmp != null) analyze(bmp);
                }
                mainHandler.postDelayed(this, CAPTURE_INTERVAL_MS);
            }
        }, CAPTURE_INTERVAL_MS);
    }

    private Bitmap grabFrame() {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return null;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenW;
            Bitmap bmp = Bitmap.createBitmap(screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
            return Bitmap.createBitmap(bmp, 0, 0, screenW, screenH);
        } catch (Throwable t) {
            Log.e(TAG, "grabFrame failed", t);
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    // ---------- board analysis ----------
    // Crops the calibrated board into an 8x8 grid and classifies each square by
    // color/occupancy heuristics. This gets you occupied-vs-empty and light/dark
    // piece color reliably; exact piece TYPE recognition needs a trained
    // classifier (FILL: swap classifySquare() for a TFLite model once you have one
    // -- the structure below is ready to drop it in).
    private void analyze(Bitmap frame) {
        int cell = boardSize / 8;
        char[][] grid = new char[8][8];
        for (int r = 0; r < 8; r++) {
            for (int f = 0; f < 8; f++) {
                int cx = boardLeft + f * cell + cell / 2;
                int cy = boardTop + r * cell + cell / 2;
                if (cx < 0 || cy < 0 || cx >= frame.getWidth() || cy >= frame.getHeight()) {
                    grid[r][f] = '.'; continue;
                }
                grid[r][f] = classifySquare(frame, cx, cy, cell);
            }
        }
        String fen = toFen(grid, youAreWhite ? 'w' : 'b');
        mainHandler.post(() -> statusText.setText("reading board... (" + fen.substring(0, Math.min(20, fen.length())) + ")"));
        queryEngine(fen);
    }

    // placeholder occupancy/brightness heuristic -- FILL with a real piece
    // classifier for production use. Returns '.' empty, 'P' generic-white, 'p' generic-black.
    private char classifySquare(Bitmap frame, int cx, int cy, int cell) {
        int sampleR = cell / 6;
        long sum = 0; int n = 0;
        for (int dy = -sampleR; dy <= sampleR; dy += 2) {
            for (int dx = -sampleR; dx <= sampleR; dx += 2) {
                int px = frame.getPixel(cx + dx, cy + dy);
                sum += (Color.red(px) + Color.green(px) + Color.blue(px)) / 3;
                n++;
            }
        }
        int avg = (int) (sum / Math.max(1, n));
        // FILL: this threshold depends on board theme; tune against your DUMP screenshots
        if (avg > 140 && avg < 210) return '.'; // looks like bare square, not a piece
        return avg > 170 ? 'P' : 'p';
    }

    private String toFen(char[][] grid, char turn) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int f = 0; f < 8; f++) {
                char c = grid[r][f];
                if (c == '.') { empty++; }
                else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(c);
                }
            }
            if (empty > 0) sb.append(empty);
            if (r != 7) sb.append('/');
        }
        sb.append(' ').append(turn).append(" - - 0 1");
        return sb.toString();
    }

    // ---------- engine ----------
    private void queryEngine(String fen) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://stockfish.online/api/s/v2.php?fen="
                        + java.net.URLEncoder.encode(fen, "UTF-8") + "&depth=12");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder resp = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) resp.append(line);
                br.close();

                String body = resp.toString();
                int idx = body.indexOf("bestmove");
                String move = idx >= 0 ? body.substring(idx + 10, idx + 14) : "n/a";
                mainHandler.post(() -> statusText.setText("best move: " + move));
            } catch (Throwable t) {
                mainHandler.post(() -> statusText.setText("engine request failed: " + t.getMessage()));
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection != null) projection.stop();
        if (overlayRoot != null) wm.removeView(overlayRoot);
        if (tapCatcher != null) wm.removeView(tapCatcher);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}