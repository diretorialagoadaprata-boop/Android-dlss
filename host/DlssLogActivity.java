package com.winlator;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public final class DlssLogActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(getIntent().getStringExtra("log_text"));
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.rgb(8, 9, 12));
        text.setTextSize(12f);
        text.setPadding(24, 24, 24, 24);
        text.setTextIsSelectable(true);
        scroll.addView(text);
        setContentView(scroll);
    }
}
