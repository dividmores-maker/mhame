package com.commi.dailytasks;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // فحص المهام في الخلفية كل ما يبقى فيه نت
        TaskCheckWorker.schedule(this);
    }
}
