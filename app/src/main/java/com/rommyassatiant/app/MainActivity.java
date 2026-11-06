package com.rommyassatiant.app;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;

public class MainActivity extends Activity {
    private static final int REQ_OVERLAY = 1, REQ_CAPTURE = 2;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Button start = new Button(this);
        start.setText("START CHESS HELPER");
        setContentView(start);
        start.setOnClickListener(v -> requestOverlayThenCapture());
    }

    private void requestOverlayThenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int reqCode, int resultCode, Intent data) {
        super.onActivityResult(reqCode, resultCode, data);
        if (reqCode == REQ_OVERLAY) {
            requestOverlayThenCapture();
        } else if (reqCode == REQ_CAPTURE && resultCode == RESULT_OK) {
            Intent svc = new Intent(this, ChessOverlayService.class);
            svc.putExtra("resultCode", resultCode);
            svc.putExtra("data", data);
            startForegroundService(svc);
            finish();
        }
    }
}