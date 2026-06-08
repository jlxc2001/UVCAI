package com.tencent.nanodetncnn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 2001;
    private static final int REQUEST_OVERLAY = 2002;

    private Spinner spinnerModel;
    private Spinner spinnerCPUGPU;
    private int currentModel = 3; // 默认 ELite0_320，适合弱性能/32 位设备
    private int currentCpuGpu = 0; // 默认 CPU

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        spinnerModel = (Spinner) findViewById(R.id.spinnerModel);
        spinnerCPUGPU = (Spinner) findViewById(R.id.spinnerCPUGPU);

        spinnerModel.setSelection(currentModel);
        spinnerCPUGPU.setSelection(currentCpuGpu);

        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentModel = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        spinnerCPUGPU.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentCpuGpu = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Button launchUsb = (Button) findViewById(R.id.buttonLaunchUsbCameraApp);
        launchUsb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launchKnownUsbCameraApp();
            }
        });

        Button start = (Button) findViewById(R.id.buttonStartScreenDetect);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScreenDetectFlow();
            }
        });

        Button stop = (Button) findViewById(R.id.buttonStopScreenDetect);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, ScreenDetectService.class);
                intent.setAction(ScreenDetectService.ACTION_STOP);
                startService(intent);
            }
        });
    }

    private void launchKnownUsbCameraApp() {
        // 兼容你发来的 USB Camera / USB Camera Pro 常见包名。
        String[] packages = new String[]{
                "com.shenyaocn.android.usbcamera",
                "com.shenyaocn.android.usbcamerapro"
        };

        for (String pkg : packages) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                Toast.makeText(this, "已打开 USB Camera App", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Toast.makeText(this, "未找到 USB Camera App，请先手动打开摄像头软件", Toast.LENGTH_LONG).show();
    }

    private void startScreenDetectFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许悬浮窗权限", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQUEST_OVERLAY);
            return;
        }

        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "无法获取 MediaProjectionManager", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY) {
            startScreenDetectFlow();
            return;
        }

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "录屏授权被取消", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, ScreenDetectService.class);
            intent.putExtra(ScreenDetectService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(ScreenDetectService.EXTRA_RESULT_DATA, data);
            intent.putExtra(ScreenDetectService.EXTRA_MODEL_ID, currentModel);
            intent.putExtra(ScreenDetectService.EXTRA_CPU_GPU, currentCpuGpu);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            Toast.makeText(this, "屏幕识别已启动，请切回 USB Camera 画面", Toast.LENGTH_SHORT).show();
        }
    }
}
