package uz.saydo.app;

import android.app.Application;

public class SayDoApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Net.start(this);
    }
}
