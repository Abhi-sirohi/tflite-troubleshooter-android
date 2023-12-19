package com.supportgenie.tflitetroubleshooter;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import com.supportgenie.tflitetroubleshooter.databinding.ActivityMainBinding;


public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding activityMainBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityMainBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(activityMainBinding.getRoot());
    }
}