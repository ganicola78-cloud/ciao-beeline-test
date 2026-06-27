package com.example.ciaobeeline.wear;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

public class MainActivity extends Activity {
    static NavView navView;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        navView = new NavView(this);
        setContentView(navView);
    }
    public static void updateFromJson(String json) {
        if (navView != null) navView.update(json);
    }
}
